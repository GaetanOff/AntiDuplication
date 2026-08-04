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
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Deletes a duplicated copy from wherever it was observed.
 *
 * <p>Deletion is deliberately conservative: the item is only removed from the
 * exact slot it was recorded in, and only if that slot still carries the same
 * UUID. Anything that moved between the sweep and the removal pass is left
 * alone and picked up by the next scan. Guessing would risk destroying a
 * legitimate item, which is far worse than catching a duplicate one scan later.
 */
public final class ItemRemover {

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;

    public ItemRemover(final AntiDuplicationPlugin plugin, final ItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
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

        final ItemLocation location = item.getItemLocation();
        final Result result = this.removeAt(item, location);
        if (result != Result.REMOVED) {
            this.plugin.getLogger().fine(String.format(
                    "Copy %s not removed at %s: %s", item.getUuid(), location.describe(), result));
        }
        return result;
    }

    private Result removeAt(final TrackedItem item, final ItemLocation location) {
        switch (location.getKind()) {
            case PLAYER_INVENTORY:
                return this.removeFromPlayerSlot(item, location, false);
            case PLAYER_ENDER_CHEST:
                return this.removeFromPlayerSlot(item, location, true);
            case PLAYER_CURSOR:
                return this.removeFromCursor(item, location);
            case CONTAINER:
                return this.removeFromContainer(item, location);
            case GROUND:
                return this.removeFromGround(item, location);
            default:
                return Result.UNSUPPORTED;
        }
    }

    private Result removeFromPlayerSlot(final TrackedItem item, final ItemLocation location, final boolean enderChest) {
        final Player player = this.resolvePlayer(location.getHolderId());
        if (player == null) {
            return Result.HOLDER_GONE;
        }
        final Inventory inventory = enderChest ? player.getEnderChest() : player.getInventory();
        final Result result = this.clearSlot(inventory, location.getSlot(), item.getUuid());
        if (result == Result.REMOVED) {
            player.sendMessage("§c[AntiDupe] A duplicated item was removed from your inventory.");
            player.updateInventory();
        }
        return result;
    }

    private Result removeFromCursor(final TrackedItem item, final ItemLocation location) {
        final Player player = this.resolvePlayer(location.getHolderId());
        if (player == null) {
            return Result.HOLDER_GONE;
        }
        final ItemStack cursor = player.getItemOnCursor();
        if (!this.matches(cursor, item.getUuid())) {
            return Result.MOVED;
        }
        player.setItemOnCursor(null);
        player.sendMessage("§c[AntiDupe] A duplicated item was removed from your cursor.");
        return Result.REMOVED;
    }

    private Result removeFromContainer(final TrackedItem item, final ItemLocation location) {
        final Location blockLocation = location.getBlockLocation();
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return Result.HOLDER_GONE;
        }
        if (!blockLocation.getWorld().isChunkLoaded(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4)) {
            return Result.HOLDER_GONE;
        }

        final BlockState state = blockLocation.getBlock().getState();
        final Inventory inventory = state instanceof Chest
                ? ((Chest) state).getBlockInventory()
                : (state instanceof InventoryHolder ? ((InventoryHolder) state).getInventory() : null);
        if (inventory == null) {
            return Result.HOLDER_GONE;
        }
        return this.clearSlot(inventory, location.getSlot(), item.getUuid());
    }

    private Result removeFromGround(final TrackedItem item, final ItemLocation location) {
        final Location blockLocation = location.getBlockLocation();
        final UUID entityId = location.getHolderId();
        if (blockLocation == null || blockLocation.getWorld() == null || entityId == null) {
            return Result.HOLDER_GONE;
        }

        for (final Entity entity : blockLocation.getWorld().getEntitiesByClass(Item.class)) {
            if (!entityId.equals(entity.getUniqueId())) {
                continue;
            }
            final Item dropped = (Item) entity;
            if (!this.matches(dropped.getItemStack(), item.getUuid())) {
                return Result.MOVED;
            }
            dropped.remove();
            return Result.REMOVED;
        }
        return Result.HOLDER_GONE;
    }

    private Result clearSlot(final Inventory inventory, final int slot, final String uuid) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return Result.MOVED;
        }
        if (!this.matches(inventory.getItem(slot), uuid)) {
            return Result.MOVED;
        }
        inventory.setItem(slot, null);
        return Result.REMOVED;
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
