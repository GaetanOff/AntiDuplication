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
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AntiDupeCommand implements CommandExecutor, TabCompleter {

    private final AntiDuplicationPlugin plugin;
    private final ItemManager itemManager;
    private final ThreadManager threadManager;
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "scan", "stats", "reload", "help", "version", "debug"
    );

    public AntiDupeCommand(final AntiDuplicationPlugin plugin, final ItemManager itemManager, final ThreadManager threadManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.threadManager = threadManager;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!sender.hasPermission("antiduplication.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }

        final String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "scan":
                return this.handleScanCommand(sender, args);
            case "stats":
                return this.handleStatsCommand(sender, args);
            case "reload":
                return this.handleReloadCommand(sender);
            case "help":
                this.sendHelp(sender);
                return true;
            case "version":
                return this.handleVersionCommand(sender);
            case "debug":
                return this.handleDebugCommand(sender, args);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                this.sendHelp(sender);
                return true;
        }
    }

    private boolean handleScanCommand(final CommandSender sender, final String[] args) {
        sender.sendMessage(ChatColor.YELLOW + "Starting manual scan...");
        final long startTime = System.currentTimeMillis();
        final CompletableFuture<ThreadManager.ScanResult> scanFuture = this.threadManager.performManualScan();
        scanFuture.whenComplete((result, exception) -> {
            if (exception != null) {
                sender.sendMessage(ChatColor.RED + "Error during scan: " + exception.getMessage());
                this.plugin.getLogger().severe("Manual scan error: " + exception.getMessage());
            } else {
                final long duration = System.currentTimeMillis() - startTime;
                if (result.isSuccess()) {
                    sender.sendMessage(ChatColor.GREEN + "Scan completed in " + duration + "ms:");
                    sender.sendMessage(ChatColor.WHITE + "- Items scanned: " + result.getItemsScanned());
                    sender.sendMessage(ChatColor.WHITE + "- Duplications found: " + result.getDuplicationsFound());
                    if (result.getDuplicationsFound() > 0) {
                        sender.sendMessage(ChatColor.YELLOW + "Duplicated items automatically removed.");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Scan failed: " + result.getErrorMessage());
                }
            }
        });
        return true;
    }

    private boolean handleStatsCommand(final CommandSender sender, final String[] args) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication Statistics ===");
        sender.sendMessage(ChatColor.WHITE + "Tracked items: " + ChatColor.GREEN + this.itemManager.getTotalItemsTracked());
        sender.sendMessage(ChatColor.WHITE + "Detected duplications: " + ChatColor.YELLOW + this.itemManager.getDuplicationsDetected());
        sender.sendMessage(ChatColor.WHITE + "Removed items: " + ChatColor.RED + this.itemManager.getItemsRemoved());
        sender.sendMessage(ChatColor.WHITE + "Active registry: " + ChatColor.BLUE + this.itemManager.getActiveRegistrySize() + " UUIDs");
        sender.sendMessage(ChatColor.WHITE + "Threads: " + ChatColor.AQUA + this.threadManager.getThreadStats());
        final String detectorStats = this.threadManager.getDetector().getPerformanceReport();
        sender.sendMessage(ChatColor.WHITE + "Detector: " + ChatColor.LIGHT_PURPLE + detectorStats);
        sender.sendMessage(ChatColor.WHITE + "Scan interval: " + ChatColor.AQUA +
                this.plugin.getConfigManager().getScanInterval() + "s");
        return true;
    }

    private boolean handleReloadCommand(final CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Reloading configuration...");
        final boolean success = this.plugin.reloadConfiguration();
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
        } else {
            sender.sendMessage(ChatColor.RED + "Error reloading configuration.");
        }
        return true;
    }

    private boolean handleVersionCommand(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication ===");
        sender.sendMessage(ChatColor.WHITE + "Version: " + ChatColor.GREEN + this.plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.WHITE + "Author: " + ChatColor.BLUE + this.plugin.getDescription().getAuthors());
        sender.sendMessage(ChatColor.WHITE + "Server: " + ChatColor.YELLOW + this.plugin.getServer().getVersion());
        final String status = this.plugin.isPluginInitialized() ?
                ChatColor.GREEN + "Active" : ChatColor.RED + "Inactive";
        sender.sendMessage(ChatColor.WHITE + "Status: " + status);
        return true;
    }

    private boolean handleDebugCommand(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /antidupe debug <info|report>");
            return true;
        }
        final String debugType = args[1].toLowerCase();
        switch (debugType) {
            case "info":
                this.sendDebugInfo(sender);
                break;
            case "report":
                final String report = this.itemManager.generateStatsReport();
                sender.sendMessage(ChatColor.GOLD + "=== Detailed Report ===");
                for (final String line : report.split("\n")) {
                    sender.sendMessage(ChatColor.WHITE + line);
                }
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown debug type: " + debugType);
                break;
        }
        return true;
    }

    private void sendDebugInfo(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Debug Information ===");
        final Runtime runtime = Runtime.getRuntime();
        final long totalMemory = runtime.totalMemory() / 1024 / 1024;
        final long freeMemory = runtime.freeMemory() / 1024 / 1024;
        final long usedMemory = totalMemory - freeMemory;
        sender.sendMessage(ChatColor.WHITE + "Used memory: " + ChatColor.YELLOW +
                usedMemory + "MB / " + totalMemory + "MB");
        sender.sendMessage(ChatColor.WHITE + "Online players: " + ChatColor.GREEN +
                this.plugin.getServer().getOnlinePlayers().size());
        sender.sendMessage(ChatColor.WHITE + "Strict mode: " + ChatColor.BLUE +
                this.plugin.getConfigManager().isStrictMode());
        sender.sendMessage(ChatColor.WHITE + "JSON logging: " + ChatColor.BLUE +
                this.plugin.getConfigManager().isLoggingEnabled());
        sender.sendMessage(ChatColor.WHITE + "Redis: " + ChatColor.BLUE +
                this.plugin.getConfigManager().isRedisEnabled());
    }

    private void sendHelp(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AntiDuplication Commands ===");
        sender.sendMessage(ChatColor.WHITE + "/antidupe scan" + ChatColor.GRAY + " - Start a manual scan");
        sender.sendMessage(ChatColor.WHITE + "/antidupe stats" + ChatColor.GRAY + " - Display statistics");
        sender.sendMessage(ChatColor.WHITE + "/antidupe reload" + ChatColor.GRAY + " - Reload configuration");
        sender.sendMessage(ChatColor.WHITE + "/antidupe version" + ChatColor.GRAY + " - Plugin information");
        sender.sendMessage(ChatColor.WHITE + "/antidupe debug <type>" + ChatColor.GRAY + " - Debug information");
        sender.sendMessage(ChatColor.WHITE + "/antidupe help" + ChatColor.GRAY + " - Display this help");
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (!sender.hasPermission("antiduplication.admin")) {
            return new ArrayList<>();
        }
        if (args.length == 1) {
            final List<String> completions = new ArrayList<>();
            final String partial = args[0].toLowerCase();
            for (final String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(partial)) {
                    completions.add(subcommand);
                }
            }
            return completions;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            final List<String> debugTypes = Arrays.asList("info", "report");
            final List<String> completions = new ArrayList<>();
            final String partial = args[1].toLowerCase();
            for (final String type : debugTypes) {
                if (type.startsWith(partial)) {
                    completions.add(type);
                }
            }
            return completions;
        }
        return new ArrayList<>();
    }
}
