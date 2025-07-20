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
import fr.gaetandev.antiduplication.storage.JsonLogger;
import fr.gaetandev.antiduplication.utils.NBTUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class ItemManager {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final JsonLogger logger;
    private final UUIDGenerator uuidGenerator;
    private final StackManager stackManager;
    private final Map<String, Set<TrackedItem>> itemRegistry;
    private final Map<String, Long> lastSeenCache;

    private final Map<String, Integer> legitimateDivisions;
    private final Map<String, Long> divisionTimestamps;

    // Statistics
    private final AtomicLong totalItemsTracked = new AtomicLong(0);
    private final AtomicLong duplicationsDetected = new AtomicLong(0);
    private final AtomicLong itemsRemoved = new AtomicLong(0);

    public ItemManager(final AntiDuplicationPlugin plugin, final ConfigManager config, final JsonLogger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.uuidGenerator = new UUIDGenerator(config.getUuidPrefix(), config.getUuidLength());
        this.stackManager = new StackManager(plugin, config, this);

        this.itemRegistry = new ConcurrentHashMap<>();
        this.lastSeenCache = new ConcurrentHashMap<>();
        this.legitimateDivisions = new ConcurrentHashMap<>();
        this.divisionTimestamps = new ConcurrentHashMap<>();
        plugin.getLogger().info("ItemManager initialized with UUID " + config.getUuidPrefix() +
                " (length: " + config.getUuidLength() + ")");
    }

    public boolean assignUUIDIfNeeded(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }

        if (this.hasValidUUID(itemStack)) {
            return false;
        }

        return this.assignBasicUUID(itemStack);
    }

    public boolean assignBasicUUID(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }

        if (this.hasValidUUID(itemStack)) {
            return false;
        }

        final String uuid = this.uuidGenerator.generateUUID();

        if (!NBTUtils.setNBTString(itemStack, this.config.getNbtTag(), uuid)) {
            this.plugin.getLogger().warning("Unable to assign UUID to item" + itemStack.getType());
            return false;
        }

        this.totalItemsTracked.incrementAndGet();

        this.plugin.getLogger().fine("UUID assigned: " + uuid + " for " + itemStack.getType());
        return true;
    }

    public String extractUUID(final ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }
        final String uuid = NBTUtils.getNBTString(itemStack, this.config.getNbtTag());
        return (uuid != null && this.uuidGenerator.isValidUUID(uuid)) ? uuid : null;
    }

    public boolean hasValidUUID(final ItemStack itemStack) {
        final String uuid = this.extractUUID(itemStack);
        return uuid != null && this.uuidGenerator.isValidUUID(uuid);
    }

    public void registerLegitimateStackDivision(final String originalUuid, final int totalAmount) {
        this.legitimateDivisions.put(originalUuid, totalAmount);
        this.divisionTimestamps.put(originalUuid, System.currentTimeMillis());

        this.plugin.getLogger().fine(String.format(
                "Division légitime enregistrée: UUID %s, quantité totale autorisée: %d",
                originalUuid, totalAmount));
    }

    private boolean isLegitimateStackDivision(final String uuid, final List<TrackedItem> items) {
        final Integer authorizedTotal = this.legitimateDivisions.get(uuid);
        if (authorizedTotal == null) {
            return false;
        }

        final int currentTotal = items.stream()
                .mapToInt(TrackedItem::getAmount)
                .sum();

        final boolean isLegitimate = currentTotal <= authorizedTotal;

        final Long divisionTime = this.divisionTimestamps.get(uuid);
        final boolean isRecent = divisionTime != null &&
                (System.currentTimeMillis() - divisionTime) < (this.config.getDivisionTolerence() * 1000L); // 30 secondes

        if (isLegitimate && isRecent) {
            this.plugin.getLogger().fine(String.format(
                    "Legitimate stack division: UUID %s, %d/%d items",
                    uuid, currentTotal, authorizedTotal));
            return true;
        }

        if (!isRecent) {
            this.legitimateDivisions.remove(uuid);
            this.divisionTimestamps.remove(uuid);
        }

        return false;
    }

    public void registerItem(final TrackedItem trackedItem) {
        final String uuid = trackedItem.getUuid();
        this.itemRegistry.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(trackedItem);
        this.lastSeenCache.put(uuid, System.currentTimeMillis());
        this.plugin.getLogger().finest("Item registered: " + trackedItem);
    }

    public void unregisterItem(final String uuid, final TrackedItem trackedItem) {
        final Set<TrackedItem> items = this.itemRegistry.get(uuid);
        if (items != null) {
            items.remove(trackedItem);
            if (items.isEmpty()) {
                this.itemRegistry.remove(uuid);
                this.lastSeenCache.remove(uuid);
            }
        }
    }

    public List<DuplicationEvent> detectDuplications() {
        final List<DuplicationEvent> events = new ArrayList<>();
        final long startTime = System.currentTimeMillis();

        for (final Map.Entry<String, Set<TrackedItem>> entry : this.itemRegistry.entrySet()) {
            final String uuid = entry.getKey();
            final Set<TrackedItem> items = entry.getValue();

            if (items.size() > 1) {
                if (!this.isLegitimateStackDivision(uuid, new ArrayList<>(items))) {
                    final DuplicationEvent event = new DuplicationEvent.Builder()
                            .duplicatedUuid(uuid)
                            .duplicatedItems(new ArrayList<>(items))
                            .detectionMethod("registry_scan")
                            .scanDurationMs(System.currentTimeMillis() - startTime)
                            .build();

                    events.add(event);
                    this.duplicationsDetected.incrementAndGet();

                    this.plugin.getLogger().warning("Duplication detected: " + event.getSummary());
                } else {
                    this.plugin.getLogger().fine("Legitimate division ignored for UUID: " + uuid);
                }
            }
        }

        return events;
    }

    public void processDuplicationEvents(final List<DuplicationEvent> events) {
        for (final DuplicationEvent event : events) {
            try {
                this.processSingleDuplicationEvent(event);
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.SEVERE,
                        "Error processing duplication " + event.getDuplicatedUuid(), e);
            }
        }
    }

    private void processSingleDuplicationEvent(final DuplicationEvent event) {
        final String uuid = event.getDuplicatedUuid();
        final List<TrackedItem> duplicatedItems = event.getDuplicatedItems();
        this.logger.logDuplicationEvent(event);
        this.notifyAdministrators(event);
        final int removed = this.removeDuplicatedItems(duplicatedItems);
        this.itemsRemoved.addAndGet(removed);
        this.itemRegistry.remove(uuid);
        this.lastSeenCache.remove(uuid);
        this.plugin.getLogger().info(String.format(
                "Duplication processed: UUID %s, %d items removed", uuid, removed));
    }

    private int removeDuplicatedItems(final List<TrackedItem> duplicatedItems) {
        int removed = 0;
        for (final TrackedItem item : duplicatedItems) {
            try {
                if (this.removeItemFromGame(item)) {
                    removed++;
                }
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.WARNING,
                        "Error removing item " + item, e);
            }
        }
        return removed;
    }

    /**
     * Must be called from the main server thread.
     */
    private boolean removeItemFromGame(final TrackedItem item) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("removeItemFromGame must be called from the main thread");
        }
        final String playerName = item.getPlayerName();
        if (playerName != null) {
            final Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) {
                return this.removeItemFromPlayerInventory(player, item);
            }
        }
        // TODO: Implement deletion from chests/containers
        // For now, we are focusing on player inventories
        return false;
    }

    private boolean removeItemFromPlayerInventory(final Player player, final TrackedItem item) {
        final Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (stack != null) {
                final String stackUuid = this.extractUUID(stack);
                if (item.getUuid().equals(stackUuid)) {
                    inventory.setItem(i, null);
                    player.sendMessage("§c[AntiDupe] Duplicated item removed from your inventory.");
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyAdministrators(final DuplicationEvent event) {
        final String message = String.format("§c[AntiDupe] %s", event.getSummary());
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission("antiduplication.admin"))
                .forEach(admin -> admin.sendMessage(message));
    }

    /**
     * Assign UUIDs to all existing items on server start.
     */
    public void initializeExistingItems() {
        this.plugin.getLogger().info("Starting initialization of existing items...");
        int processed = 0;
        int assigned = 0;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            for (final ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    processed++;
                    if (this.assignUUIDIfNeeded(item)) {
                        assigned++;
                    }
                }
            }
        }
        this.plugin.getLogger().info(String.format(
                "Initialization complete: %d items processed, %d UUIDs assigned", processed, assigned));
    }

    /**
     * Remove outdated entries from cache and registry.
     */
    public void cleanupOldItems() {
        final long cutoffTime = System.currentTimeMillis() - this.config.getCacheTtlMillis();

        this.lastSeenCache.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);

        final long divisionCutoff = System.currentTimeMillis() - 300000; // 5 minutes
        this.divisionTimestamps.entrySet().removeIf(entry -> entry.getValue() < divisionCutoff);
        this.legitimateDivisions.keySet().retainAll(this.divisionTimestamps.keySet());

        final Set<String> validUuids = new HashSet<>(this.lastSeenCache.keySet());
        this.itemRegistry.keySet().retainAll(validUuids);
    }

    public long getTotalItemsTracked() {
        return this.totalItemsTracked.get();
    }

    public long getDuplicationsDetected() {
        return this.duplicationsDetected.get();
    }

    public long getItemsRemoved() {
        return this.itemsRemoved.get();
    }

    public int getActiveRegistrySize() {
        return this.itemRegistry.size();
    }

    public StackManager getStackManager() {
        return this.stackManager;
    }

    public Map<String, Integer> getRegistryStats() {
        return this.itemRegistry.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().size()
                ));
    }

    public String generateStatsReport() {
        final StringBuilder report = new StringBuilder();
        report.append("=== AntiDuplication Statistics ===\n");
        report.append("Tracked items: ").append(this.totalItemsTracked.get()).append("\n");
        report.append("Detected duplications: ").append(this.duplicationsDetected.get()).append("\n");
        report.append("Removed items: ").append(this.itemsRemoved.get()).append("\n");
        report.append("Active registry: ").append(this.itemRegistry.size()).append(" UUIDs\n");
        report.append("Cache: ").append(this.lastSeenCache.size()).append(" entries\n");
        final UUIDGenerator.GeneratorStats genStats = this.uuidGenerator.getStats();
        report.append("Generator: ").append(genStats.toString()).append("\n");
        return report.toString();
    }
}
