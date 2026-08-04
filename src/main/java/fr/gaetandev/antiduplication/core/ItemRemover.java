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
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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
 */
public final class ItemRemover {

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;

    public ItemRemover(final AntiDuplicationPlugin plugin, final ItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
    }

    /**
     * Whether the recorded location still holds this exact UUID.
     *
     * <p>This is what makes a multi-tick sweep safe. Players and containers are
     * scanned in different ticks, so an item carried from an inventory to a chest
     * in between appears in both slices and looks duplicated. Re-checking every
     * copy on the main thread, right before acting, filters those ghosts out: an
     * item can only be in one place at a time, so at most as many copies survive
     * the check as really exist.
     *
     * <p>Must be called from the main thread.
     */
    public boolean stillPresent(final TrackedItem item) {
        final ItemLocation location = item.getItemLocation();
        if (location.getKind() == ItemLocation.Kind.GROUND) {
            return this.findDroppedEntity(item) != null;
        }
        if (location.getKind() == ItemLocation.Kind.PLAYER_CURSOR) {
            final Player player = this.resolvePlayer(location.getHolderId());
            return player != null && this.matches(player.getItemOnCursor(), item.getUuid());
        }

        final Inventory inventory = this.resolveInventory(location);
        if (inventory == null) {
            return false;
        }
        final int slot = location.getSlot();
        return slot >= 0 && slot < inventory.getSize()
                && this.matches(inventory.getItem(slot), item.getUuid());
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
        switch (location.getKind()) {
            case GROUND:
                final Item dropped = this.findDroppedEntity(item);
                if (dropped == null) {
                    return Result.MOVED;
                }
                dropped.remove();
                return Result.REMOVED;

            case PLAYER_CURSOR:
                final Player holder = this.resolvePlayer(location.getHolderId());
                if (holder == null) {
                    return Result.HOLDER_GONE;
                }
                if (!this.matches(holder.getItemOnCursor(), item.getUuid())) {
                    return Result.MOVED;
                }
                holder.setItemOnCursor(null);
                holder.sendMessage("§c[AntiDupe] A duplicated item was removed from your cursor.");
                return Result.REMOVED;

            case PLAYER_INVENTORY:
            case PLAYER_ENDER_CHEST:
            case CONTAINER:
                return this.clearSlot(item, location);

            default:
                return Result.UNSUPPORTED;
        }
    }

    private Result clearSlot(final TrackedItem item, final ItemLocation location) {
        final Inventory inventory = this.resolveInventory(location);
        if (inventory == null) {
            return Result.HOLDER_GONE;
        }
        final int slot = location.getSlot();
        if (slot < 0 || slot >= inventory.getSize() || !this.matches(inventory.getItem(slot), item.getUuid())) {
            return Result.MOVED;
        }

        inventory.setItem(slot, null);
        if (location.getKind().isPlayerHeld()) {
            final Player player = this.resolvePlayer(location.getHolderId());
            if (player != null) {
                player.sendMessage("§c[AntiDupe] A duplicated item was removed from your inventory.");
                player.updateInventory();
            }
        }
        return Result.REMOVED;
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

    private Item findDroppedEntity(final TrackedItem item) {
        final World world = resolveWorld(item.getItemLocation().getBlockLocation());
        final UUID entityId = item.getItemLocation().getHolderId();
        if (world == null || entityId == null) {
            return null;
        }

        for (final Item dropped : world.getEntitiesByClass(Item.class)) {
            if (entityId.equals(dropped.getUniqueId()) && !dropped.isDead()
                    && this.matches(dropped.getItemStack(), item.getUuid())) {
                return dropped;
            }
        }
        return null;
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
        /** The player logged out or the container is no longer loaded. */
        HOLDER_GONE,
        /** The location kind cannot be addressed for removal. */
        UNSUPPORTED;

        public boolean isRemoved() {
            return this == REMOVED;
        }
    }
}
