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
import fr.gaetandev.antiduplication.model.DuplicationEvent;
import fr.gaetandev.antiduplication.model.TrackedItem;
import fr.gaetandev.antiduplication.threading.ThreadManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class DuplicationDetector {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;
    private ThreadManager threadManager;

    // Avoid rescanning the same UUIDs too frequently; helps limit load in rapid-scan scenarios
    private final Map<String, Long> lastScanCache = new ConcurrentHashMap<>();

    // Tracks all known UUIDs seen during the current or previous scans
    private final Map<String, Set<String>> knownUuids = new ConcurrentHashMap<>();

    private volatile long lastScanDuration = 0;
    private volatile int lastScanItemCount = 0;
    private volatile int lastScanDuplicationCount = 0;

    public DuplicationDetector(final AntiDuplicationPlugin plugin, final ConfigManager config, final ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
    }

    public void initialize() {
        this.threadManager = this.plugin.getThreadManager();
        if (this.threadManager == null) {
            throw new IllegalStateException("ThreadManager is not available");
        }
        this.plugin.getLogger().info("DuplicationDetector initialized");
    }

    // Async full scan triggered manually or on timer
    public CompletableFuture<List<DuplicationEvent>> performFullScan() {
        return this.threadManager.submitTask(() -> {
            final long startTime = System.currentTimeMillis();
            try {
                this.plugin.getLogger().fine("Starting duplication scan...");
                this.lastScanItemCount = 0;
                this.lastScanDuplicationCount = 0;
                final Map<String, List<TrackedItem>> itemsByUuid = this.collectAllItems();
                final List<DuplicationEvent> duplications = this.findDuplications(itemsByUuid);
                this.lastScanDuration = System.currentTimeMillis() - startTime;
                this.lastScanDuplicationCount = duplications.size();
                this.plugin.getLogger().fine(String.format(
                        "Scan completed: %d items analyzed, %d duplications detected in %dms",
                        this.lastScanItemCount, this.lastScanDuplicationCount, this.lastScanDuration));
                return duplications;
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error during duplication scan", e);
                return Collections.emptyList();
            }
        });
    }

    private Map<String, List<TrackedItem>> collectAllItems() {
        final Map<String, List<TrackedItem>> itemsByUuid = new ConcurrentHashMap<>();
        final long timeoutTime = System.currentTimeMillis() + this.config.getScanTimeout();

        // Avoid scanning if the time limit is exceeded during player inventory scanning
        this.scanPlayerInventories(itemsByUuid, timeoutTime);

        // Container scanning is skipped or simplified by default for performance
        if (System.currentTimeMillis() < timeoutTime) {
            this.scanLoadedContainers(itemsByUuid, timeoutTime);
        }
        return itemsByUuid;
    }

    private void scanPlayerInventories(final Map<String, List<TrackedItem>> itemsByUuid, final long timeoutTime) {
        final Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        for (final Player player : onlinePlayers) {
            if (System.currentTimeMillis() > timeoutTime) {
                this.plugin.getLogger().warning("Timeout reached during player scanning");
                break;
            }
            try {
                this.scanPlayerInventory(player, itemsByUuid);
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.WARNING,
                        "Error scanning inventory of " + player.getName(), e);
            }
        }
    }

    private void scanPlayerInventory(final Player player, final Map<String, List<TrackedItem>> itemsByUuid) {
        final Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack item = inventory.getItem(slot);
            if (item == null) {
                continue;
            }
            final String uuid = this.itemManager.extractUUID(item);
            if (uuid != null) {
                final TrackedItem trackedItem = new TrackedItem.Builder()
                        .uuid(uuid)
                        .fromItemStack(item)
                        .container("PLAYER_INVENTORY")
                        .playerName(player.getName())
                        .build();
                itemsByUuid.computeIfAbsent(uuid, k -> new ArrayList<>()).add(trackedItem);
                this.lastScanItemCount++;
            } else if (this.config.shouldBlockNoUuid() && this.config.isStrictMode()) {
                // In strict mode, items missing UUIDs are assigned one and re-scanned recursively
                if (this.itemManager.assignUUIDIfNeeded(item)) {
                    this.scanPlayerInventory(player, itemsByUuid);
                    return;
                }
            }
        }
    }

    private void scanLoadedContainers(final Map<String, List<TrackedItem>> itemsByUuid, final long timeoutTime) {
        for (final World world : Bukkit.getWorlds()) {
            if (System.currentTimeMillis() > timeoutTime) {
                this.plugin.getLogger().warning("Timeout reached during container scanning");
                break;
            }
            try {
                // Currently disabled or stubbed for performance reasons
                this.scanWorldContainers(world, itemsByUuid, timeoutTime);
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.WARNING,
                        "Error scanning containers in " + world.getName(), e);
            }
        }
    }

    private void scanWorldContainers(final World world, final Map<String, List<TrackedItem>> itemsByUuid, final long timeoutTime) {
        // Note: This method is simplified to avoid overloading the server
        // In a complete implementation, we would scan all loaded chunks
        // and their tile entities (chests, furnaces, etc.)
        // For now, we are limiting it to players to avoid performance issues
        this.plugin.getLogger().finest("Container scanning temporarily disabled for performance");
    }

    private List<DuplicationEvent> findDuplications(final Map<String, List<TrackedItem>> itemsByUuid) {
        final List<DuplicationEvent> duplications = new ArrayList<>();
        for (final Map.Entry<String, List<TrackedItem>> entry : itemsByUuid.entrySet()) {
            final String uuid = entry.getKey();
            final List<TrackedItem> items = entry.getValue();
            // Only consider duplicates where the same UUID is found in multiple instances
            if (items.size() > 1) {
                final DuplicationEvent event = this.createDuplicationEvent(uuid, items);
                duplications.add(event);
                this.plugin.getLogger().warning(String.format(
                        "Duplication found - UUID: %s, Copies: %d", uuid, items.size()));
                for (final TrackedItem item : items) {
                    this.plugin.getLogger().warning("  -> " + item.getLocationDescription());
                }
            }
        }
        return duplications;
    }

    private DuplicationEvent createDuplicationEvent(final String uuid, final List<TrackedItem> duplicatedItems) {
        final DuplicationEvent.Severity severity = this.calculateSeverity(duplicatedItems);
        // Analyze locations for additional information
        final String additionalInfo = this.analyzeLocations(duplicatedItems);
        return new DuplicationEvent.Builder()
                .duplicatedUuid(uuid)
                .duplicatedItems(duplicatedItems)
                .detectionTime(Instant.now())
                .detectionMethod("comprehensive_scan")
                .severity(severity)
                .scanDurationMs(this.lastScanDuration)
                .additionalInfo(additionalInfo)
                .build();
    }

    private DuplicationEvent.Severity calculateSeverity(final List<TrackedItem> items) {
        final int count = items.size();
        final boolean hasValuableItems = items.stream()
                .anyMatch(item -> this.isValuableItem(item.getMaterial()));
        // Adjust severity based on item value and duplication count
        if (count >= 10 || (count >= 5 && hasValuableItems)) {
            return DuplicationEvent.Severity.CRITICAL;
        } else if (count >= 5 || (count >= 3 && hasValuableItems)) {
            return DuplicationEvent.Severity.HIGH;
        } else if (count >= 3) {
            return DuplicationEvent.Severity.MEDIUM;
        } else {
            return DuplicationEvent.Severity.LOW;
        }
    }

    private boolean isValuableItem(final org.bukkit.Material material) {
        // Basic logic to detect high-value or enchanted gear
        switch (material) {
            case DIAMOND:
            case DIAMOND_BLOCK:
            case EMERALD:
            case EMERALD_BLOCK:
            case GOLD_INGOT:
            case GOLD_BLOCK:
            case IRON_INGOT:
            case IRON_BLOCK:
                return true;
            default:
                return material.name().contains("_SWORD") ||
                        material.name().contains("_PICKAXE") ||
                        material.name().contains("_AXE") ||
                        material.name().contains("_HOE") ||
                        material.name().contains("_HELMET") ||
                        material.name().contains("_CHESTPLATE") ||
                        material.name().contains("_LEGGINGS") ||
                        material.name().contains("_BOOTS");
        }
    }

    private String analyzeLocations(final List<TrackedItem> items) {
        final Set<String> players = new HashSet<>();
        final Set<String> containers = new HashSet<>();
        for (final TrackedItem item : items) {
            if (item.getPlayerName() != null) {
                players.add(item.getPlayerName());
            }
            containers.add(item.getContainer());
        }
        final StringBuilder info = new StringBuilder();
        // Useful metadata to inform admins about how widespread the duplication is
        if (players.size() == 1) {
            info.append("All items with a single player (").append(players.iterator().next()).append("). ");
        } else if (players.size() > 1) {
            info.append("Items spread across ").append(players.size()).append(" players. ");
        }
        if (containers.size() == 1) {
            info.append("Same container type: ").append(containers.iterator().next()).append(".");
        }
        return info.toString();
    }

    public CompletableFuture<List<DuplicationEvent>> scanPlayer(final Player player) {
        return this.threadManager.submitTask(() -> {
            final Map<String, List<TrackedItem>> itemsByUuid = new ConcurrentHashMap<>();
            this.scanPlayerInventory(player, itemsByUuid);
            return this.findDuplications(itemsByUuid);
        });
    }

    public boolean isRecentScanAvailable() {
        // Used to avoid triggering too frequent scans which may be expensive
        return (System.currentTimeMillis() - this.lastScanDuration) < this.config.getScanIntervalMillis() / 2;
    }

    public long getLastScanDuration() {
        return this.lastScanDuration;
    }

    public int getLastScanItemCount() {
        return this.lastScanItemCount;
    }

    public int getLastScanDuplicationCount() {
        return this.lastScanDuplicationCount;
    }

    public String getPerformanceReport() {
        return String.format(
                "Detector Performance - Last scan: %dms, Items: %d, Duplications: %d",
                this.lastScanDuration, this.lastScanItemCount, this.lastScanDuplicationCount
        );
    }
}