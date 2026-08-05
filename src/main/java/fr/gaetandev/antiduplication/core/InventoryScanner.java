/*
 * AntiDuplication: a plugin for Minecraft servers.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fr.gaetandev.antiduplication.core;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Walks the inventories of the server and returns an immutable snapshot.
 *
 * <p>Item stacks may only be touched from the main thread, so the sweep runs
 * there — but never in one go. Work is sliced into units (one player, one chunk)
 * and only {@code scanning.batch-size} units run per tick, which keeps the cost
 * per tick bounded whatever the size of the world. Analysis of the result then
 * happens off-thread.
 *
 * <p>Everything a chunk holds is scanned as one unit: block containers, entity
 * inventories, entity equipment and dropped items. Grouping them per chunk rather
 * than per world is what keeps a world with tens of thousands of entities from
 * landing in a single tick.
 *
 * <p>Missing UUIDs are stamped as the sweep goes: this is the one place where the
 * plugin already holds a live stack on the right thread.
 */
public final class InventoryScanner {

    /** Equipment slot index standing for the hand, after the armour slots. */
    static final int HAND_EQUIPMENT_SLOT = 4;

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;
    private final StackManager stackManager;

    public InventoryScanner(final AntiDuplicationPlugin plugin, final ConfigManager config,
                            final ItemManager itemManager, final StackManager stackManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.stackManager = stackManager;
    }

    /**
     * Starts a sweep.
     *
     * @param includeWorlds whether to walk the loaded chunks as well as the online
     *                      players; players alone are far cheaper and cover the
     *                      surface items move through most
     * @return the snapshot, completed on the main thread once the sweep is over
     */
    public CompletableFuture<ScanSnapshot> scanServer(final boolean includeWorlds) {
        final CompletableFuture<ScanSnapshot> result = new CompletableFuture<>();
        this.runOnMainThread(() -> new Session(result, includeWorlds).start());
        return result;
    }

    /**
     * Sweeps a single player, for {@code /antidupe scan <player>}.
     */
    public CompletableFuture<ScanSnapshot> scanPlayer(final Player player) {
        final CompletableFuture<ScanSnapshot> result = new CompletableFuture<>();
        this.runOnMainThread(() -> {
            final ScanSnapshot.Builder builder = new ScanSnapshot.Builder();
            final long start = System.currentTimeMillis();
            try {
                this.scanPlayer(player, builder);
                result.complete(builder.durationMs(System.currentTimeMillis() - start).build());
            } catch (final RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void runOnMainThread(final Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    /**
     * One sweep in progress; every method runs on the main thread.
     */
    private final class Session implements Runnable {

        private final CompletableFuture<ScanSnapshot> result;
        private final boolean includeWorlds;
        private final ScanSnapshot.Builder builder = new ScanSnapshot.Builder();
        private final Deque<Player> players = new ArrayDeque<>();
        private final Deque<Chunk> chunks = new ArrayDeque<>();

        private long startedAt;
        private long deadline;
        private BukkitTask task;

        Session(final CompletableFuture<ScanSnapshot> result, final boolean includeWorlds) {
            this.result = result;
            this.includeWorlds = includeWorlds;
        }

        void start() {
            this.startedAt = System.currentTimeMillis();
            this.deadline = this.startedAt + InventoryScanner.this.config.getScanTimeout();
            this.players.addAll(Bukkit.getOnlinePlayers());

            if (this.includeWorlds) {
                for (final World world : Bukkit.getWorlds()) {
                    for (final Chunk chunk : world.getLoadedChunks()) {
                        this.chunks.add(chunk);
                    }
                }
            }

            // Run the first slice straight away, then one per tick.
            this.task = Bukkit.getScheduler().runTaskTimer(InventoryScanner.this.plugin, this, 0L, 1L);
        }

        @Override
        public void run() {
            int budget = InventoryScanner.this.config.getBatchSize();
            try {
                while (budget-- > 0) {
                    if (System.currentTimeMillis() > this.deadline) {
                        this.builder.markTruncated();
                        InventoryScanner.this.plugin.getLogger().warning(String.format(
                                "Scan budget of %dms exhausted: %d players and %d chunks left unscanned",
                                InventoryScanner.this.config.getScanTimeout(),
                                this.players.size(), this.chunks.size()));
                        this.finish();
                        return;
                    }
                    if (!this.scanNextUnit()) {
                        this.finish();
                        return;
                    }
                }
            } catch (final RuntimeException e) {
                InventoryScanner.this.plugin.getLogger().log(Level.SEVERE, "Error during inventory sweep", e);
                this.finish();
            }
        }

        /**
         * @return {@code false} when there is nothing left to sweep
         */
        private boolean scanNextUnit() {
            final Player player = this.players.poll();
            if (player != null) {
                if (player.isOnline()) {
                    InventoryScanner.this.scanPlayer(player, this.builder);
                }
                return true;
            }
            final Chunk chunk = this.chunks.poll();
            if (chunk != null) {
                if (chunk.isLoaded()) {
                    InventoryScanner.this.scanChunk(chunk, this.builder);
                }
                return true;
            }
            return false;
        }

        private void finish() {
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
            this.result.complete(this.builder
                    .durationMs(System.currentTimeMillis() - this.startedAt)
                    .build());
        }
    }

    private void scanPlayer(final Player player, final ScanSnapshot.Builder builder) {
        if (this.itemManager.isExempt(player)) {
            this.plugin.debug(player.getName() + " holds antiduplication.bypass, inventory left untouched");
            return;
        }

        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();

        // Slot indices above the storage range address armour and, on modern
        // servers, the off hand. Addressing them through getItem/setItem keeps
        // one code path for every version and, crucially, never reports the same
        // slot twice: getContents() covers armour on modern servers but not on
        // 1.8, so mixing it with getArmorContents() would double count.
        this.scanInventory(player.getInventory(), builder,
                ItemLocation.Kind.PLAYER_INVENTORY, playerId, playerName, null);

        this.scanInventory(player.getEnderChest(), builder,
                ItemLocation.Kind.PLAYER_ENDER_CHEST, playerId, playerName, null);

        final ItemStack cursor = player.getItemOnCursor();
        final StackOutcome outcome = this.handleStack(cursor, builder, ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_CURSOR, playerId, playerName, ItemLocation.NO_SLOT));
        if (outcome == StackOutcome.DELETE) {
            player.setItemOnCursor(null);
        } else if (outcome == StackOutcome.MODIFIED) {
            player.setItemOnCursor(cursor);
        }
    }

    private void scanChunk(final Chunk chunk, final ScanSnapshot.Builder builder) {
        this.scanBlockContainers(chunk, builder);
        this.scanEntities(chunk, builder);
    }

    private void scanBlockContainers(final Chunk chunk, final ScanSnapshot.Builder builder) {
        final BlockState[] blockEntities;
        try {
            blockEntities = chunk.getTileEntities();
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to read the block entities of a chunk", e);
            return;
        }

        for (final BlockState state : blockEntities) {
            try {
                final Inventory inventory = resolveBlockInventory(state);
                if (inventory != null) {
                    this.scanInventory(inventory, builder, ItemLocation.Kind.CONTAINER,
                            null, state.getType().name(), state.getLocation());
                }
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Unable to scan a container", e);
            }
        }
    }

    /**
     * A double chest reports the same joined inventory from both of its halves,
     * which would make every item in it look duplicated. Reading the block
     * inventory keeps each half to its own slots.
     */
    private static Inventory resolveBlockInventory(final BlockState state) {
        if (state instanceof Chest) {
            return ((Chest) state).getBlockInventory();
        }
        return state instanceof InventoryHolder ? ((InventoryHolder) state).getInventory() : null;
    }

    /**
     * Items an entity carries: a chest minecart or chested horse inventory, the
     * armour and weapon a mob or armour stand wears, the item an item frame
     * displays, and items lying on the ground.
     */
    private void scanEntities(final Chunk chunk, final ScanSnapshot.Builder builder) {
        final Entity[] entities;
        try {
            entities = chunk.getEntities();
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to read the entities of a chunk", e);
            return;
        }

        for (final Entity entity : entities) {
            try {
                this.scanEntity(entity, builder);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Unable to scan entity " + entity.getType(), e);
            }
        }
    }

    private void scanEntity(final Entity entity, final ScanSnapshot.Builder builder) {
        if (entity.isDead()) {
            return;
        }
        // Players are scanned as their own unit; a Player is also an
        // InventoryHolder, so letting one through here would double count.
        if (entity instanceof HumanEntity) {
            return;
        }

        if (entity instanceof Item) {
            this.scanDroppedItem((Item) entity, builder);
            return;
        }
        if (entity instanceof ItemFrame) {
            this.scanItemFrame((ItemFrame) entity, builder);
            return;
        }
        if (entity instanceof InventoryHolder) {
            this.scanInventory(((InventoryHolder) entity).getInventory(), builder,
                    ItemLocation.Kind.ENTITY_CONTAINER, entity.getUniqueId(),
                    entity.getType().name(), entity.getLocation());
        }
        if (entity instanceof LivingEntity) {
            this.scanEquipment((LivingEntity) entity, builder);
        }
    }

    private void scanDroppedItem(final Item dropped, final ScanSnapshot.Builder builder) {
        final ItemStack stack = dropped.getItemStack();
        final StackOutcome outcome = this.handleStack(stack, builder,
                ItemLocation.ofGround(dropped.getUniqueId(), dropped.getLocation()));
        if (outcome == StackOutcome.DELETE) {
            dropped.remove();
        } else if (outcome == StackOutcome.MODIFIED) {
            dropped.setItemStack(stack);
        }
    }

    private void scanItemFrame(final ItemFrame frame, final ScanSnapshot.Builder builder) {
        final ItemStack displayed = frame.getItem();
        final StackOutcome outcome = this.handleStack(displayed, builder, ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_HELD, frame.getUniqueId(), frame.getType().name(),
                frame.getLocation(), ItemLocation.NO_SLOT));
        if (outcome == StackOutcome.DELETE) {
            frame.setItem(null);
        } else if (outcome == StackOutcome.MODIFIED) {
            frame.setItem(displayed);
        }
    }

    /**
     * Armour stands are the usual way a duplicated set of armour gets parked in
     * the world, and mobs can be dressed in one too.
     */
    private void scanEquipment(final LivingEntity entity, final ScanSnapshot.Builder builder) {
        final EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }

        final ItemStack[] armor = equipment.getArmorContents();
        boolean armorChanged = false;
        for (int slot = 0; slot < armor.length; slot++) {
            final StackOutcome outcome = this.handleStack(armor[slot], builder, this.equipmentLocation(entity, slot));
            if (outcome == StackOutcome.DELETE) {
                armor[slot] = null;
                armorChanged = true;
            } else if (outcome == StackOutcome.MODIFIED) {
                armorChanged = true;
            }
        }
        if (armorChanged) {
            equipment.setArmorContents(armor);
        }

        final ItemStack held = equipment.getItemInHand();
        final StackOutcome outcome = this.handleStack(held, builder,
                this.equipmentLocation(entity, HAND_EQUIPMENT_SLOT));
        if (outcome == StackOutcome.DELETE) {
            equipment.setItemInHand(null);
        } else if (outcome == StackOutcome.MODIFIED) {
            equipment.setItemInHand(held);
        }
    }

    private ItemLocation equipmentLocation(final LivingEntity entity, final int slot) {
        return ItemLocation.ofEntity(ItemLocation.Kind.ENTITY_EQUIPMENT, entity.getUniqueId(),
                entity.getType().name(), entity.getLocation(), slot);
    }

    private void scanInventory(final Inventory inventory, final ScanSnapshot.Builder builder,
                               final ItemLocation.Kind kind, final UUID holderId,
                               final String holderName, final Location location) {
        if (inventory == null) {
            return;
        }
        final int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            final StackOutcome outcome = this.handleStack(stack, builder,
                    locationFor(kind, holderId, holderName, location, slot));
            if (outcome == StackOutcome.DELETE) {
                inventory.setItem(slot, null);
            } else if (outcome == StackOutcome.MODIFIED) {
                inventory.setItem(slot, stack);
            }
        }
    }

    private static ItemLocation locationFor(final ItemLocation.Kind kind, final UUID holderId,
                                            final String holderName, final Location location, final int slot) {
        if (kind == ItemLocation.Kind.CONTAINER) {
            return ItemLocation.ofContainer(holderName, location, slot);
        }
        return kind.isEntityHeld()
                ? ItemLocation.ofEntity(kind, holderId, holderName, location, slot)
                : ItemLocation.ofPlayer(kind, holderId, holderName, slot);
    }

    /**
     * Records one stack, stamping or sanitising it as needed.
     */
    private StackOutcome handleStack(final ItemStack stack, final ScanSnapshot.Builder builder,
                                     final ItemLocation location) {
        if (stack == null || stack.getType() == Material.AIR) {
            return StackOutcome.UNCHANGED;
        }

        StackOutcome outcome = StackOutcome.UNCHANGED;

        // Checked on every item, tracked or not: a stack above the legal size is a
        // duplication symptom in its own right, and it is precisely the stackable
        // items left out of UUID tracking that it shows up on.
        final int legalSize = this.stackManager.getOversizedExcess(stack);
        if (legalSize >= 0) {
            builder.countOversizedStack();
            this.plugin.getLogger().warning(String.format(
                    "Oversized stack: %s x%d (max %d) at %s",
                    stack.getType(), stack.getAmount(), legalSize, location.describe()));
            this.plugin.getJsonLogger().logEvent("oversized_stack", String.format(
                    "Item: %s x%d, Max: %d, Location: %s, Trimmed: %s",
                    stack.getType(), stack.getAmount(), legalSize, location.describe(),
                    this.config.isStrictMode()));
            if (this.config.isStrictMode()) {
                stack.setAmount(legalSize);
                outcome = StackOutcome.MODIFIED;
            }
        }

        // Cheap material test before any item data lookup: on a typical server the
        // vast majority of stacks are not tracked at all.
        if (!this.stackManager.isTrackable(stack)) {
            return outcome;
        }

        String uuid = this.itemManager.extractUUID(stack);
        if (uuid == null) {
            if (this.config.isStrictMode() && this.config.shouldBlockNoUuid()) {
                builder.countUntaggedRejected();
                this.plugin.getLogger().warning(String.format(
                        "Untagged item removed (security.block-no-uuid): %s x%d at %s",
                        stack.getType(), stack.getAmount(), location.describe()));
                return StackOutcome.DELETE;
            }
            // The item was just read and carries nothing, so stamp directly
            // instead of paying for a second item data lookup.
            uuid = this.itemManager.stampFresh(stack);
            if (uuid == null) {
                return outcome;
            }
            builder.countAssignedUuid();
            outcome = StackOutcome.MODIFIED;
        }

        builder.add(new TrackedItem.Builder()
                .uuid(uuid)
                .fromItemStack(stack)
                .location(location)
                .build());
        return outcome;
    }

    /**
     * What the caller must do with the slot after a stack was examined.
     */
    private enum StackOutcome {
        UNCHANGED,
        MODIFIED,
        DELETE
    }
}
