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

package fr.gaetandev.antiduplication.listeners;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import fr.gaetandev.antiduplication.core.ItemManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Stamps items as they enter the game and records the actions worth auditing.
 *
 * <p>Rather than trying to mutate the right {@link ItemStack} inside each event —
 * which is a guessing game across click types, and outright wrong for stacks the
 * server owns, such as a recipe result — the listener schedules a stamping pass
 * over the affected player's inventory on the next tick. By then the server has
 * finished moving items around, every stack is the real one, and a single pass
 * covers all the cases. Passes are debounced per player, so a burst of clicks
 * costs one pass.
 */
public final class ItemEventListener implements Listener {

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;
    private final ConfigManager config;

    /** Players with a stamping pass already scheduled for the next tick. */
    private final Set<UUID> pendingStampPasses = ConcurrentHashMap.newKeySet();

    public ItemEventListener(final AntiDuplicationPlugin plugin, final ItemManager itemManager,
                             final ConfigManager config) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        this.scheduleStampPass(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        this.pendingStampPasses.remove(event.getPlayer().getUniqueId());
    }

    /**
     * The crafting result must never be stamped through {@code getRecipe()}: that
     * object is the server's shared recipe template, so writing a UUID on it
     * would hand the same identifier to every future craft of that recipe and
     * make the plugin report a duplication on each one.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(final CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            this.scheduleStampPass((Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(final PlayerPickupItemEvent event) {
        try {
            final ItemStack stack = event.getItem().getItemStack();
            if (this.itemManager.assignUUIDIfNeeded(stack)) {
                event.getItem().setItemStack(stack);
            }
            this.scheduleStampPass(event.getPlayer());
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error handling an item pickup", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(final PlayerDropItemEvent event) {
        try {
            final ItemStack stack = event.getItemDrop().getItemStack();
            if (this.itemManager.assignUUIDIfNeeded(stack)) {
                event.getItemDrop().setItemStack(stack);
            }
            if (this.config.shouldMonitorCreative() && event.getPlayer().getGameMode() == GameMode.CREATIVE) {
                this.logCreativeAction("creative_drop", event.getPlayer(), stack);
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error handling an item drop", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        try {
            if (this.config.shouldTrackStackableItems()) {
                this.recordStackDivision(event);
            }
            if (this.config.isStrictMode() && event.getAction() == InventoryAction.CLONE_STACK) {
                this.logSuspiciousAction("stack_clone", event.getWhoClicked().getName(), event.getCurrentItem());
            }
            if (event.getWhoClicked() instanceof Player) {
                this.scheduleStampPass((Player) event.getWhoClicked());
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error handling an inventory click", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            this.scheduleStampPass((Player) event.getWhoClicked());
        }
    }

    /**
     * Items conjured in the creative inventory bypass every other event, which
     * makes this the single most useful hook for creative auditing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreativeInventory(final InventoryCreativeEvent event) {
        try {
            if (this.config.shouldMonitorCreative() && event.getWhoClicked() instanceof Player) {
                this.logCreativeAction("creative_set_slot", (Player) event.getWhoClicked(), event.getCursor());
            }
            if (event.getWhoClicked() instanceof Player) {
                this.scheduleStampPass((Player) event.getWhoClicked());
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error handling a creative inventory action", e);
        }
    }

    /**
     * Death scatters a player's items as dropped entities; stamping the survivors
     * keeps the registry aligned with reality.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        try {
            for (final ItemStack drop : event.getDrops()) {
                this.itemManager.assignUUIDIfNeeded(drop);
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error handling death drops", e);
        }
    }

    /**
     * Splitting or merging a stack copies its item data, UUID included. Recording
     * the operation lets the detector tell the pieces of a split stack from a
     * genuine duplication. Only meaningful when stackable tracking is on.
     */
    private void recordStackDivision(final InventoryClickEvent event) {
        final ItemStack clicked = event.getCurrentItem();
        final ItemStack cursor = event.getCursor();

        switch (event.getAction()) {
            case PICKUP_HALF:
            case PICKUP_SOME:
            case PICKUP_ONE:
                this.registerDivision(clicked, clicked == null ? 0 : clicked.getAmount());
                break;
            case PLACE_SOME:
            case PLACE_ONE:
            case PLACE_ALL:
                if (cursor != null && clicked != null) {
                    final String cursorUuid = this.itemManager.extractUUID(cursor);
                    if (cursorUuid != null && cursorUuid.equals(this.itemManager.extractUUID(clicked))) {
                        this.itemManager.registerLegitimateStackDivision(cursorUuid,
                                cursor.getAmount() + clicked.getAmount());
                    }
                }
                break;
            default:
                break;
        }
    }

    private void registerDivision(final ItemStack stack, final int totalAmount) {
        final String uuid = this.itemManager.extractUUID(stack);
        if (uuid != null) {
            this.itemManager.registerLegitimateStackDivision(uuid, totalAmount);
        }
    }

    /**
     * Queues a next-tick stamping pass for this player, at most one at a time.
     */
    private void scheduleStampPass(final Player player) {
        final UUID playerId = player.getUniqueId();
        if (!this.pendingStampPasses.add(playerId)) {
            return;
        }
        try {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                this.pendingStampPasses.remove(playerId);
                this.stampInventory(player);
            });
        } catch (final RuntimeException e) {
            // The plugin is unloading; the next full scan will stamp these items.
            this.pendingStampPasses.remove(playerId);
        }
    }

    /**
     * Stamps every trackable item the player holds. Runs on the main thread.
     */
    private void stampInventory(final Player player) {
        if (!player.isOnline()) {
            return;
        }
        try {
            final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
            final int size = inventory.getSize();
            for (int slot = 0; slot < size; slot++) {
                final ItemStack stack = inventory.getItem(slot);
                if (stack != null && stack.getType() != Material.AIR
                        && this.itemManager.assignUUIDIfNeeded(stack)) {
                    inventory.setItem(slot, stack);
                }
            }

            final ItemStack cursor = player.getItemOnCursor();
            if (this.itemManager.assignUUIDIfNeeded(cursor)) {
                player.setItemOnCursor(cursor);
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error stamping the inventory of " + player.getName(), e);
        }
    }

    private void logCreativeAction(final String type, final Player player, final ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        this.plugin.getJsonLogger().logEvent(type, String.format("Player: %s, Item: %s x%d",
                player.getName(), stack.getType(), stack.getAmount()));

        if (this.config.shouldMonitorAdminCommands()) {
            this.plugin.notifyAdmins(String.format("§e[AntiDupe] %s handled %dx %s in creative mode",
                    player.getName(), stack.getAmount(), stack.getType()));
        }
    }

    private void logSuspiciousAction(final String type, final String playerName, final ItemStack stack) {
        this.plugin.getJsonLogger().logEvent(type, String.format("Player: %s, Item: %s",
                playerName, stack == null ? "none" : stack.getType() + " x" + stack.getAmount()));
    }
}
