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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;

public final class ItemEventListener implements Listener {

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;
    private final ConfigManager config;

    public ItemEventListener(final AntiDuplicationPlugin plugin, final ItemManager itemManager, final ConfigManager config) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                for (final ItemStack item : event.getPlayer().getInventory().getContents()) {
                    if (item != null) {
                        this.itemManager.assignUUIDIfNeeded(item);
                    }
                }
                this.plugin.getLogger().fine("Items initialized for " + event.getPlayer().getName());
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.WARNING,
                        "Error initializing items for " + event.getPlayer().getName(), e);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftItem(final CraftItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        try {
            final ItemStack result = event.getRecipe().getResult();
            if (result != null) {
                this.itemManager.assignUUIDIfNeeded(result);
                this.plugin.getLogger().finest("UUID assigned to crafted item: " + result.getType());
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during crafting", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemPickup(final PlayerPickupItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        try {
            final ItemStack item = event.getItem().getItemStack();
            if (this.itemManager.assignUUIDIfNeeded(item)) {
                event.getItem().setItemStack(item);
            }
            if (this.config.isStrictMode()) {
                this.checkSuspiciousPickup(event);
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during pickup", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(final PlayerDropItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        try {
            final ItemStack item = event.getItemDrop().getItemStack();
            this.itemManager.assignUUIDIfNeeded(item);
            if (this.config.shouldMonitorCreative() &&
                    event.getPlayer().getGameMode() == GameMode.CREATIVE) {
                this.logCreativeActivity(event);
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during drop", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        try {
            final ItemStack currentItem = event.getCurrentItem();
            final ItemStack cursor = event.getCursor();
            if (currentItem != null) {
                this.itemManager.assignUUIDIfNeeded(currentItem);
            }
            if (cursor != null) {
                this.itemManager.assignUUIDIfNeeded(cursor);
            }
            if (this.config.isStrictMode()) {
                this.checkSuspiciousInventoryAction(event);
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during inventory click", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        try {
            final ItemStack oldCursor = event.getOldCursor();
            if (oldCursor != null) {
                this.itemManager.assignUUIDIfNeeded(oldCursor);
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during inventory drag", e);
        }
    }

    private void checkSuspiciousPickup(final PlayerPickupItemEvent event) {
        final String playerName = event.getPlayer().getName();
        final ItemStack item = event.getItem().getItemStack();
        this.plugin.getJsonLogger().logEvent("suspicious_pickup",
                "Player: " + playerName + ", Item: " + item.getType() + " x" + item.getAmount());
    }

    private void logCreativeActivity(final PlayerDropItemEvent event) {
        final String playerName = event.getPlayer().getName();
        final ItemStack item = event.getItemDrop().getItemStack();
        this.plugin.getJsonLogger().logEvent("creative_drop",
                "Player: " + playerName + ", Item: " + item.getType() + " x" + item.getAmount());
        if (this.config.shouldMonitorAdminCommands()) {
            this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("antiduplication.admin"))
                    .forEach(admin -> admin.sendMessage(
                            "§e[AntiDupe] " + playerName + " dropped " +
                                    item.getAmount() + "x " + item.getType() + " in creative mode"));
        }
    }

    private void checkSuspiciousInventoryAction(final InventoryClickEvent event) {
        if (event.getAction() == InventoryAction.CLONE_STACK) {
            // Stack clone (middle-click in creative)
            this.plugin.getJsonLogger().logEvent("stack_clone",
                    "Player: " + event.getWhoClicked().getName() +
                            ", Item: " + (event.getCurrentItem() != null ? event.getCurrentItem().getType() : "null"));
        }
        // Other checks can be added here
    }
}