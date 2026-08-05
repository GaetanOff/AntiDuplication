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
import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Checks and deletes a copy at the exact place it was observed.
 *
 * <p>Deletion is deliberately conservative: a copy is only removed from the slot
 * it was recorded in, and only if that slot still carries the same UUID.
 * Anything that moved between the sweep and the removal pass is left alone and
 * picked up by the next scan. Guessing would risk destroying a legitimate item,
 * which is far worse than catching a duplicate one scan later.
 *
 * <p>Reading and clearing go through the same accessors, so a location that can
 * be verified can also be cleared, and vice versa.
 */
public final class ItemRemover {

    /**
     * How far around its recorded position an entity is looked for. Wide enough
     * to absorb the drift of one scan interval, narrow enough not to walk the
     * whole world.
     */
    private static final double ENTITY_SEARCH_RADIUS = 12.0D;

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;

    public ItemRemover(final AntiDuplicationPlugin plugin, final ItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
    }

    /**
     * Whether the recorded location still holds this exact UUID.
     *
     * <p>This is what makes a multi-tick sweep safe. Players and chunks are
     * scanned in different ticks, so an item carried from an inventory to a chest
     * in between appears in both slices and looks duplicated. Re-checking every
     * copy on the main thread, right before acting, filters those ghosts out: an
     * item can only be in one place at a time, so at most as many copies survive
     * the check as really exist.
     *
     * <p>Must be called from the main thread.
     */
    public boolean stillPresent(final TrackedItem item) {
        return this.matches(this.readItem(item.getItemLocation()), item.getUuid());
    }

    /**
     * Must be called from the main thread.
     *
     * @return the outcome of the attempt, for statistics and logging
     */
    public Result remove(final TrackedItem item) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Items may only be removed from the main thread");
        }

        final Result result = this.removeAt(item);
        if (result != Result.REMOVED) {
            this.plugin.debug(String.format("Copy %s not removed at %s: %s",
                    item.getUuid(), item.getItemLocation().describe(), result));
        }
        return result;
    }

    private Result removeAt(final TrackedItem item) {
        final ItemLocation location = item.getItemLocation();
        if (!this.matches(this.readItem(location), item.getUuid())) {
            // Either the holder is gone or the slot changed hands; both mean the
            // next scan gets another look.
            return this.holderExists(location) ? Result.MOVED : Result.HOLDER_GONE;
        }

        switch (location.getKind()) {
            case PLAYER_INVENTORY:
            case PLAYER_ENDER_CHEST:
            case CONTAINER:
            case ENTITY_CONTAINER: {
                final Inventory inventory = this.resolveInventory(location);
                if (inventory == null) {
                    return Result.HOLDER_GONE;
                }
                inventory.setItem(location.getSlot(), null);
                this.notifyOwner(location, "inventory");
                return Result.REMOVED;
            }
            case PLAYER_CURSOR: {
                final Player player = this.resolvePlayer(location.getHolderId());
                if (player == null) {
                    return Result.HOLDER_GONE;
                }
                player.setItemOnCursor(null);
                this.notifyOwner(location, "cursor");
                return Result.REMOVED;
            }
            case ENTITY_EQUIPMENT:
                return this.clearEquipment(location);
            case ENTITY_HELD: {
                final ItemFrame frame = this.resolveEntity(location, ItemFrame.class);
                if (frame == null) {
                    return Result.HOLDER_GONE;
                }
                frame.setItem(null);
                return Result.REMOVED;
            }
            case GROUND: {
                final Item dropped = this.resolveEntity(location, Item.class);
                if (dropped == null) {
                    return Result.HOLDER_GONE;
                }
                dropped.remove();
                return Result.REMOVED;
            }
            default:
                return Result.UNSUPPORTED;
        }
    }

    /**
     * Reads whatever currently sits at a location, without changing anything.
     *
     * @return the live stack, or {@code null} when the holder or slot is gone
     */
    private ItemStack readItem(final ItemLocation location) {
        switch (location.getKind()) {
            case PLAYER_INVENTORY:
            case PLAYER_ENDER_CHEST:
            case CONTAINER:
            case ENTITY_CONTAINER: {
                final Inventory inventory = this.resolveInventory(location);
                final int slot = location.getSlot();
                return inventory != null && slot >= 0 && slot < inventory.getSize()
                        ? inventory.getItem(slot) : null;
            }
            case PLAYER_CURSOR: {
                final Player player = this.resolvePlayer(location.getHolderId());
                return player != null ? player.getItemOnCursor() : null;
            }
            case ENTITY_EQUIPMENT: {
                final LivingEntity entity = this.resolveEntity(location, LivingEntity.class);
                final EntityEquipment equipment = entity != null ? entity.getEquipment() : null;
                return equipment != null ? equipmentItem(equipment, location.getSlot()) : null;
            }
            case ENTITY_HELD: {
                final ItemFrame frame = this.resolveEntity(location, ItemFrame.class);
                return frame != null ? frame.getItem() : null;
            }
            case GROUND: {
                final Item dropped = this.resolveEntity(location, Item.class);
                return dropped != null ? dropped.getItemStack() : null;
            }
            default:
                return null;
        }
    }

    /**
     * Tells a vanished holder apart from a slot that merely changed, so the
     * statistics distinguish "player logged out" from "item moved".
     */
    private boolean holderExists(final ItemLocation location) {
        if (location.getKind().isPlayerHeld()) {
            return this.resolvePlayer(location.getHolderId()) != null;
        }
        if (location.getKind() == ItemLocation.Kind.CONTAINER) {
            return resolveContainerInventory(location.getBlockLocation()) != null;
        }
        return this.resolveEntity(location, Entity.class) != null;
    }

    private Result clearEquipment(final ItemLocation location) {
        final LivingEntity entity = this.resolveEntity(location, LivingEntity.class);
        final EntityEquipment equipment = entity != null ? entity.getEquipment() : null;
        if (equipment == null) {
            return Result.HOLDER_GONE;
        }

        final int slot = location.getSlot();
        if (slot == InventoryScanner.HAND_EQUIPMENT_SLOT) {
            equipment.setItemInHand(null);
            return Result.REMOVED;
        }
        final ItemStack[] armor = equipment.getArmorContents();
        if (slot < 0 || slot >= armor.length) {
            return Result.MOVED;
        }
        armor[slot] = null;
        equipment.setArmorContents(armor);
        return Result.REMOVED;
    }

    private static ItemStack equipmentItem(final EntityEquipment equipment, final int slot) {
        if (slot == InventoryScanner.HAND_EQUIPMENT_SLOT) {
            return equipment.getItemInHand();
        }
        final ItemStack[] armor = equipment.getArmorContents();
        return slot >= 0 && slot < armor.length ? armor[slot] : null;
    }

    /**
     * @return the live inventory behind a location, or {@code null} when the
     *         holder is offline, unloaded or no longer a container
     */
    private Inventory resolveInventory(final ItemLocation location) {
        switch (location.getKind()) {
            case PLAYER_INVENTORY: {
                final Player player = this.resolvePlayer(location.getHolderId());
                return player != null ? player.getInventory() : null;
            }
            case PLAYER_ENDER_CHEST: {
                final Player player = this.resolvePlayer(location.getHolderId());
                return player != null ? player.getEnderChest() : null;
            }
            case CONTAINER:
                return resolveContainerInventory(location.getBlockLocation());
            case ENTITY_CONTAINER: {
                final InventoryHolder holder = this.resolveEntity(location, InventoryHolder.class);
                return holder != null ? holder.getInventory() : null;
            }
            default:
                return null;
        }
    }

    private static Inventory resolveContainerInventory(final Location blockLocation) {
        final World world = resolveWorld(blockLocation);
        if (world == null) {
            return null;
        }
        if (!world.isChunkLoaded(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4)) {
            return null;
        }

        final BlockState state = blockLocation.getBlock().getState();
        // The same accessor as the sweep, so slot indices line up: the joined
        // inventory of a double chest would shift them.
        if (state instanceof Chest) {
            return ((Chest) state).getBlockInventory();
        }
        return state instanceof InventoryHolder ? ((InventoryHolder) state).getInventory() : null;
    }

    /**
     * Finds the recorded entity around where it was seen.
     *
     * <p>Bounded to a radius rather than sweeping every entity of the world: this
     * runs for every reported copy an entity holds, and an entity that travelled
     * further than that is simply left for the next scan.
     *
     * @return the entity, narrowed to the expected type, or {@code null}
     */
    private <T> T resolveEntity(final ItemLocation location, final Class<T> type) {
        final Location position = location.getBlockLocation();
        final UUID entityId = location.getHolderId();
        if (entityId == null || resolveWorld(position) == null) {
            return null;
        }

        for (final Entity candidate : position.getWorld().getNearbyEntities(
                position, ENTITY_SEARCH_RADIUS, ENTITY_SEARCH_RADIUS, ENTITY_SEARCH_RADIUS)) {
            if (entityId.equals(candidate.getUniqueId()) && !candidate.isDead()
                    && type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }

    /**
     * @return the world of this location, or {@code null} when it is gone;
     *         {@link Location#getWorld()} throws on an unloaded world
     */
    private static World resolveWorld(final Location location) {
        if (location == null) {
            return null;
        }
        try {
            return location.getWorld();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private void notifyOwner(final ItemLocation location, final String place) {
        if (!location.getKind().isPlayerHeld()) {
            return;
        }
        final Player player = this.resolvePlayer(location.getHolderId());
        if (player != null) {
            player.sendMessage("§c[AntiDupe] A duplicated item was removed from your " + place + ".");
            player.updateInventory();
        }
    }

    private boolean matches(final ItemStack stack, final String uuid) {
        return stack != null && uuid.equals(this.itemManager.extractUUID(stack));
    }

    private Player resolvePlayer(final UUID playerId) {
        if (playerId == null) {
            return null;
        }
        final Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? player : null;
    }

    /**
     * Why a removal succeeded or was skipped.
     */
    public enum Result {
        /** The copy was deleted. */
        REMOVED,
        /** The slot no longer holds that UUID; the next scan will catch up. */
        MOVED,
        /** The player logged out, or the container or entity is gone. */
        HOLDER_GONE,
        /** The location kind cannot be addressed for removal. */
        UNSUPPORTED;

        public boolean isRemoved() {
            return this == REMOVED;
        }
    }
}
