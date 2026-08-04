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
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Owns the identity of tracked items: stamping them, remembering who held them,
 * and applying the removals decided by the detector.
 */
public final class ItemManager {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final JsonLogger logger;
    private final UUIDGenerator uuidGenerator;
    private final StackManager stackManager;
    private final StackDivisionTracker divisionTracker;
    private final ItemRemover itemRemover;

    /**
     * Last known holder of each UUID. This is what lets the plugin tell the
     * original from the copies: whichever copy sits where the UUID was already
     * seen is the one that gets to stay.
     */
    private final Map<String, OwnerRecord> owners = new ConcurrentHashMap<>();

    private final AtomicLong totalItemsTracked = new AtomicLong();
    private final AtomicLong duplicationsDetected = new AtomicLong();
    private final AtomicLong itemsRemoved = new AtomicLong();
    private final AtomicLong removalsSkipped = new AtomicLong();
    private final AtomicLong ghostDuplications = new AtomicLong();

    public ItemManager(final AntiDuplicationPlugin plugin, final ConfigManager config, final JsonLogger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.uuidGenerator = new UUIDGenerator(config.getUuidPrefix(), config.getUuidLength());
        this.stackManager = new StackManager(plugin, config);
        this.divisionTracker = new StackDivisionTracker(
                config::getDivisionToleranceMillis, config::getMaxDivisionsPerStack);
        this.itemRemover = new ItemRemover(plugin, this);

        plugin.getLogger().info(String.format(
                "ItemManager initialized: tag '%s', prefix '%s', length %d, item data via %s",
                config.getNbtTag(), config.getUuidPrefix(), config.getUuidLength(), NBTUtils.getProviderName()));
    }

    /**
     * Stamps a fresh UUID on an item that does not have one yet. Must be called
     * from the main thread, since it writes to a live stack.
     *
     * @return the assigned UUID, the existing one when already stamped, or
     *         {@code null} when the item cannot or should not be tracked
     */
    public String assignUUID(final ItemStack itemStack) {
        if (!this.stackManager.isTrackable(itemStack)) {
            return null;
        }
        final String existing = this.extractUUID(itemStack);
        if (existing != null) {
            return existing;
        }

        final String uuid = this.uuidGenerator.generateUUID();
        if (!NBTUtils.setNBTString(itemStack, this.config.getNbtTag(), uuid)) {
            this.plugin.getLogger().warning("Unable to stamp a UUID on " + itemStack.getType());
            return null;
        }
        this.totalItemsTracked.incrementAndGet();
        return uuid;
    }

    /**
     * @return {@code true} when a UUID was newly assigned
     */
    public boolean assignUUIDIfNeeded(final ItemStack itemStack) {
        if (!this.stackManager.isTrackable(itemStack) || this.extractUUID(itemStack) != null) {
            return false;
        }
        return this.assignUUID(itemStack) != null;
    }

    /**
     * @return the plugin UUID carried by the item, or {@code null} when absent or malformed
     */
    public String extractUUID(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        final String uuid = NBTUtils.getNBTString(itemStack, this.config.getNbtTag());
        return uuid != null && this.uuidGenerator.isValidUUID(uuid) ? uuid : null;
    }

    /**
     * Records that a stack was legitimately split, so its pieces are not
     * reported as a duplication for the configured tolerance window.
     */
    public void registerLegitimateStackDivision(final String uuid, final int totalAmount) {
        this.divisionTracker.register(uuid, totalAmount);
    }

    /**
     * Remembers where a UUID currently lives.
     */
    void rememberOwner(final String uuid, final String ownerKey) {
        if (!this.config.shouldCacheResults()) {
            return;
        }
        this.owners.put(uuid, new OwnerRecord(ownerKey));
    }

    /**
     * @return the holder this UUID was last seen at, or {@code null} when unknown
     */
    String knownOwner(final String uuid) {
        final OwnerRecord record = this.owners.get(uuid);
        return record != null ? record.ownerKey : null;
    }

    void countDetectedDuplication() {
        this.duplicationsDetected.incrementAndGet();
    }

    /**
     * Deletes the extra copies of every event and reports them. Must run on the
     * main thread.
     */
    public int applyDuplicationEvents(final List<DuplicationEvent> events) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Duplication events may only be applied from the main thread");
        }

        int removed = 0;
        for (final DuplicationEvent event : events) {
            try {
                removed += this.applyDuplicationEvent(event);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.SEVERE,
                        "Error processing duplication " + event.getDuplicatedUuid(), e);
            }
        }
        return removed;
    }

    private int applyDuplicationEvent(final DuplicationEvent event) {
        final List<TrackedItem> live = this.confirmStillDuplicated(event);
        if (live.isEmpty()) {
            return 0;
        }

        final TrackedItem retained = live.contains(event.getRetainedItem())
                ? event.getRetainedItem() : live.get(0);

        int removed = 0;
        int skipped = 0;
        for (final TrackedItem copy : live) {
            if (copy.equals(retained)) {
                continue;
            }
            if (this.itemRemover.remove(copy).isRemoved()) {
                removed++;
            } else {
                skipped++;
            }
        }

        this.itemsRemoved.addAndGet(removed);
        this.removalsSkipped.addAndGet(skipped);

        // Logged after the fact so the audit trail states what was really done
        // rather than what was intended.
        this.logger.logDuplicationEvent(event, removed, skipped);
        this.notifyAdministrators(event);
        this.plugin.debug(event.getDetailedDescription());

        // The retained copy keeps the UUID, so ownership is rewritten rather
        // than dropped; the division budget is reset since the stack is whole.
        this.rememberOwner(event.getDuplicatedUuid(), event.getRetainedItem().getItemLocation().getOwnerKey());
        this.divisionTracker.forget(event.getDuplicatedUuid());

        this.plugin.getLogger().warning(String.format(
                "Duplication handled: UUID %s, %d copies removed, %d skipped, kept %s",
                event.getDuplicatedUuid(), removed, skipped, retained.getLocationDescription()));
        return removed;
    }

    /**
     * Re-checks every reported copy against the live world.
     *
     * <p>The sweep spans several ticks, so an item carried from a player's
     * inventory to a chest between two slices is recorded in both and looks
     * duplicated. An item can only be in one place at a time, so re-checking here
     * — on the main thread, immediately before acting — leaves at most as many
     * copies as really exist. Fewer than two means the duplication was a ghost.
     *
     * @return the copies that are genuinely present, or an empty list when there
     *         is no duplication to act on
     */
    private List<TrackedItem> confirmStillDuplicated(final DuplicationEvent event) {
        final List<TrackedItem> live = new ArrayList<>(event.getDuplicateCount());
        for (final TrackedItem copy : event.getDuplicatedItems()) {
            if (this.itemRemover.stillPresent(copy)) {
                live.add(copy);
            }
        }

        if (live.size() < 2) {
            this.ghostDuplications.incrementAndGet();
            this.plugin.debug(String.format(
                    "UUID %s is no longer duplicated (%d of %d copies still present); "
                            + "the item moved while the scan was running",
                    event.getDuplicatedUuid(), live.size(), event.getDuplicateCount()));
            return Collections.emptyList();
        }
        return live;
    }

    private void notifyAdministrators(final DuplicationEvent event) {
        final String message = "§c[AntiDupe] " + event.getSummary();
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("antiduplication.admin")) {
                online.sendMessage(message);
            }
        }
    }

    /**
     * Drops registry entries that have not been seen for a while, and expired
     * stack division records.
     *
     * @return the number of forgotten UUIDs
     */
    public int cleanupOldItems() {
        final long ttl = this.config.getCacheTtlMillis();
        final int before = this.owners.size();
        this.owners.values().removeIf(record -> record.isExpired(ttl));
        final int forgotten = before - this.owners.size();
        final int expiredDivisions = this.divisionTracker.evictExpired();

        if (forgotten > 0 || expiredDivisions > 0) {
            this.plugin.getLogger().fine(String.format(
                    "Cleanup: %d UUIDs forgotten, %d division records expired", forgotten, expiredDivisions));
        }
        return forgotten;
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

    public long getRemovalsSkipped() {
        return this.removalsSkipped.get();
    }

    /**
     * @return reported duplications that turned out to be one item observed twice
     *         while it was being moved
     */
    public long getGhostDuplications() {
        return this.ghostDuplications.get();
    }

    public int getActiveRegistrySize() {
        return this.owners.size();
    }

    public StackManager getStackManager() {
        return this.stackManager;
    }

    public StackDivisionTracker getDivisionTracker() {
        return this.divisionTracker;
    }

    public String generateStatsReport() {
        final StringBuilder report = new StringBuilder();
        report.append("=== AntiDuplication Statistics ===\n");
        report.append("Item data backend: ").append(NBTUtils.getProviderName()).append("\n");
        report.append("UUIDs assigned: ").append(this.totalItemsTracked.get()).append("\n");
        report.append("Duplications detected: ").append(this.duplicationsDetected.get()).append("\n");
        report.append("Copies removed: ").append(this.itemsRemoved.get()).append("\n");
        report.append("Removals skipped (item moved): ").append(this.removalsSkipped.get()).append("\n");
        report.append("Reports dismissed after re-check: ").append(this.ghostDuplications.get()).append("\n");
        report.append("Known UUIDs: ").append(this.owners.size()).append("\n");
        report.append("Pending stack divisions: ").append(this.divisionTracker.size()).append("\n");
        report.append(this.stackManager.generateStackStats()).append("\n");
        report.append("Generator: ").append(this.uuidGenerator.getStats()).append("\n");
        return report.toString();
    }

    /**
     * Where a UUID was last seen, with the timestamp used for expiry.
     */
    private static final class OwnerRecord {
        private final String ownerKey;
        private final long seenAt;

        OwnerRecord(final String ownerKey) {
            this.ownerKey = ownerKey;
            this.seenAt = System.currentTimeMillis();
        }

        boolean isExpired(final long ttlMillis) {
            return System.currentTimeMillis() - this.seenAt > ttlMillis;
        }
    }

    /**
     * Exposed for the detector, which needs a reference date when a UUID was
     * already known before this sweep.
     */
    Instant firstSeen(final String uuid) {
        final OwnerRecord record = this.owners.get(uuid);
        return record != null ? Instant.ofEpochMilli(record.seenAt) : null;
    }
}
