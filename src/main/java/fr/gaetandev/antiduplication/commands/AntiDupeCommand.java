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

package fr.gaetandev.antiduplication.commands;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.core.ItemManager;
import fr.gaetandev.antiduplication.threading.ThreadManager;
import fr.gaetandev.antiduplication.utils.NBTUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class AntiDupeCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "antiduplication.admin";
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "scan", "stats", "check", "reload", "help", "version", "debug");
    private static final List<String> DEBUG_TYPES = Arrays.asList("info", "report", "item");

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;
    private final ThreadManager threadManager;

    public AntiDupeCommand(final AntiDuplicationPlugin plugin, final ItemManager itemManager,
                           final ThreadManager threadManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.threadManager = threadManager;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan":
                return this.handleScan(sender, args);
            case "stats":
                return this.handleStats(sender);
            case "check":
                return this.handleCheck(sender);
            case "reload":
                return this.handleReload(sender);
            case "version":
                return this.handleVersion(sender);
            case "debug":
                return this.handleDebug(sender, args);
            case "help":
                this.sendHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
                this.sendHelp(sender);
                return true;
        }
    }

    private boolean handleScan(final CommandSender sender, final String[] args) {
        if (args.length >= 2) {
            final Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "Scanning " + target.getName() + "...");
            this.reportWhenComplete(sender, this.threadManager.runPlayerScan(target));
            return true;
        }

        if (this.threadManager.getDetector().isScanInProgress()) {
            sender.sendMessage(ChatColor.RED + "A scan is already running, please wait for it to finish.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Starting a full scan...");
        this.reportWhenComplete(sender, this.threadManager.runScan("manual_scan"));
        return true;
    }

    /**
     * Scans finish on a worker thread; the reply is sent back on the main thread
     * so the API is only touched where it is legal to.
     */
    private void reportWhenComplete(final CommandSender sender,
                                    final java.util.concurrent.CompletableFuture<ThreadManager.ScanResult> future) {
        future.whenComplete((result, throwable) -> this.threadManager.runOnMainThread(() -> {
            if (throwable != null) {
                sender.sendMessage(ChatColor.RED + "Scan error: " + throwable.getMessage());
                return;
            }
            if (!result.isSuccess()) {
                sender.sendMessage(ChatColor.RED + "Scan failed: " + result.getErrorMessage());
                return;
            }
            sender.sendMessage(ChatColor.GREEN + "Scan completed in " + result.getDuration() + "ms:");
            sender.sendMessage(ChatColor.WHITE + "- Items tracked: " + result.getItemsScanned());
            sender.sendMessage(ChatColor.WHITE + "- Duplications found: " + result.getDuplicationsFound());
            sender.sendMessage(ChatColor.WHITE + "- Copies removed: " + result.getCopiesRemoved());
        }));
    }

    private boolean handleStats(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication Statistics ===");
        sender.sendMessage(ChatColor.WHITE + "UUIDs assigned: " + ChatColor.GREEN
                + this.itemManager.getTotalItemsTracked());
        sender.sendMessage(ChatColor.WHITE + "Duplications detected: " + ChatColor.YELLOW
                + this.itemManager.getDuplicationsDetected());
        sender.sendMessage(ChatColor.WHITE + "Copies removed: " + ChatColor.RED
                + this.itemManager.getItemsRemoved());
        sender.sendMessage(ChatColor.WHITE + "Removals skipped: " + ChatColor.GRAY
                + this.itemManager.getRemovalsSkipped() + " (item had moved)");
        sender.sendMessage(ChatColor.WHITE + "Known UUIDs: " + ChatColor.BLUE
                + this.itemManager.getActiveRegistrySize());
        sender.sendMessage(ChatColor.WHITE + "Scanner: " + ChatColor.AQUA
                + this.threadManager.getScannerTask().getPerformanceReport());
        sender.sendMessage(ChatColor.WHITE + "Detector: " + ChatColor.LIGHT_PURPLE
                + this.threadManager.getDetector().getPerformanceReport());
        sender.sendMessage(ChatColor.WHITE + "Threads: " + ChatColor.AQUA + this.threadManager.getThreadStats());
        sender.sendMessage(ChatColor.WHITE + "Scan interval: " + ChatColor.AQUA
                + this.plugin.getConfigManager().getScanInterval() + "s");
        return true;
    }

    /**
     * Shows the tracking state of the item in hand, which is the quickest way to
     * confirm the plugin is doing its job.
     */
    private boolean handleCheck(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can inspect a held item.");
            return true;
        }

        final ItemStack held = ((Player) sender).getInventory().getItemInHand();
        if (held == null || held.getType() == org.bukkit.Material.AIR) {
            sender.sendMessage(ChatColor.RED + "You are not holding anything.");
            return true;
        }

        final String uuid = this.itemManager.extractUUID(held);
        sender.sendMessage(ChatColor.GOLD + "=== Held item ===");
        sender.sendMessage(ChatColor.WHITE + "Type: " + ChatColor.AQUA + held.getType() + " x" + held.getAmount());
        sender.sendMessage(ChatColor.WHITE + "Max stack size: " + ChatColor.AQUA
                + this.itemManager.getStackManager().getMaxStackSize(held.getType()));
        sender.sendMessage(ChatColor.WHITE + "Tracked: " + (this.itemManager.getStackManager().isTrackable(held)
                ? ChatColor.GREEN + "yes" : ChatColor.GRAY + "no (stackable item)"));
        sender.sendMessage(ChatColor.WHITE + "UUID: " + (uuid != null
                ? ChatColor.GREEN + uuid : ChatColor.RED + "none"));
        return true;
    }

    private boolean handleReload(final CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Reloading configuration...");
        if (this.plugin.reloadConfiguration()) {
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully.");
        } else {
            sender.sendMessage(ChatColor.RED + "Error reloading configuration, see the console.");
        }
        return true;
    }

    private boolean handleVersion(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication ===");
        sender.sendMessage(ChatColor.WHITE + "Version: " + ChatColor.GREEN
                + this.plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.WHITE + "Author: " + ChatColor.BLUE
                + this.plugin.getDescription().getAuthors());
        sender.sendMessage(ChatColor.WHITE + "Server: " + ChatColor.YELLOW + this.plugin.getServer().getVersion());
        sender.sendMessage(ChatColor.WHITE + "Item data backend: " + ChatColor.AQUA + NBTUtils.getProviderName());
        sender.sendMessage(ChatColor.WHITE + "Status: " + (this.plugin.isPluginInitialized()
                ? ChatColor.GREEN + "active" : ChatColor.RED + "inactive"));
        return true;
    }

    private boolean handleDebug(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /antidupe debug <info|report|item>");
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info":
                this.sendDebugInfo(sender);
                return true;
            case "report":
                sender.sendMessage(ChatColor.GOLD + "=== Detailed report ===");
                for (final String line : this.itemManager.generateStatsReport().split("\n")) {
                    sender.sendMessage(ChatColor.WHITE + line);
                }
                return true;
            case "item":
                return this.sendRawItemData(sender);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown debug type: " + args[1]);
                return true;
        }
    }

    private boolean sendRawItemData(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can inspect a held item.");
            return true;
        }
        final ItemStack held = ((Player) sender).getInventory().getItemInHand();
        if (held == null || held.getType() == org.bukkit.Material.AIR) {
            sender.sendMessage(ChatColor.RED + "You are not holding anything.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "Custom data: " + ChatColor.WHITE
                + NBTUtils.getNBTDebugString(held));
        return true;
    }

    private void sendDebugInfo(final CommandSender sender) {
        final Runtime runtime = Runtime.getRuntime();
        final long totalMemory = runtime.totalMemory() / 1024 / 1024;
        final long usedMemory = totalMemory - runtime.freeMemory() / 1024 / 1024;

        sender.sendMessage(ChatColor.GOLD + "=== Debug information ===");
        sender.sendMessage(ChatColor.WHITE + "Memory: " + ChatColor.YELLOW + usedMemory + "MB / " + totalMemory + "MB");
        sender.sendMessage(ChatColor.WHITE + "Online players: " + ChatColor.GREEN
                + this.plugin.getServer().getOnlinePlayers().size());
        sender.sendMessage(ChatColor.WHITE + "Strict mode: " + ChatColor.BLUE
                + this.plugin.getConfigManager().isStrictMode());
        sender.sendMessage(ChatColor.WHITE + "Tracking stackable items: " + ChatColor.BLUE
                + this.plugin.getConfigManager().shouldTrackStackableItems());
        sender.sendMessage(ChatColor.WHITE + "JSON logging: " + ChatColor.BLUE
                + this.plugin.getConfigManager().isLoggingEnabled()
                + ChatColor.GRAY + " (queued " + this.plugin.getJsonLogger().getQueuedEntries()
                + ", written " + this.plugin.getJsonLogger().getWrittenEntries()
                + ", dropped " + this.plugin.getJsonLogger().getDroppedEntries() + ")");
        sender.sendMessage(ChatColor.WHITE + "Shared registry: " + ChatColor.BLUE
                + (this.plugin.getRedisRegistry().isAvailable()
                ? "connected as " + this.plugin.getRedisRegistry().getServerId() : "off"));
        sender.sendMessage(ChatColor.WHITE + "Pending stack divisions: " + ChatColor.BLUE
                + this.itemManager.getDivisionTracker().size());
    }

    private void sendHelp(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication commands ===");
        sender.sendMessage(ChatColor.WHITE + "/antidupe scan [player]" + ChatColor.GRAY
                + " - Scan the server, or one player");
        sender.sendMessage(ChatColor.WHITE + "/antidupe stats" + ChatColor.GRAY + " - Display statistics");
        sender.sendMessage(ChatColor.WHITE + "/antidupe check" + ChatColor.GRAY
                + " - Show the tracking state of the held item");
        sender.sendMessage(ChatColor.WHITE + "/antidupe reload" + ChatColor.GRAY + " - Reload the configuration");
        sender.sendMessage(ChatColor.WHITE + "/antidupe version" + ChatColor.GRAY + " - Plugin information");
        sender.sendMessage(ChatColor.WHITE + "/antidupe debug <info|report|item>" + ChatColor.GRAY
                + " - Debug information");
        sender.sendMessage(ChatColor.WHITE + "/antidupe help" + ChatColor.GRAY + " - Display this help");
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return startingWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return startingWith(DEBUG_TYPES, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
            final List<String> names = new ArrayList<>();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return startingWith(names, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> startingWith(final List<String> candidates, final String partial) {
        final String prefix = partial.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}
