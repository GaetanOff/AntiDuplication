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
import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;
import fr.gaetandev.antiduplication.storage.RedisRegistry;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Turns a scan snapshot into duplication events.
 *
 * <p>The snapshot is collected on the main thread by {@link InventoryScanner};
 * everything here is pure computation over immutable values and therefore runs
 * on a worker thread. Applying the result goes back to the main thread.
 */
public final class DuplicationDetector {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;
    private final InventoryScanner scanner;
    private final RedisRegistry redisRegistry;

    /**
     * The snapshot future is completed on the main thread, so the analysis stage
     * must be handed to this executor explicitly; a plain thenApply would run the
     * whole analysis, Redis round trips included, on the server thread.
     */
    private final Executor analysisExecutor;

    /** Guards against overlapping sweeps, whether scheduled or manual. */
    private final AtomicBoolean scanInProgress = new AtomicBoolean();

    private volatile long lastScanStartedAt;
    private volatile long lastScanDuration;
    private volatile int lastScanItemCount;
    private volatile int lastScanDuplicationCount;

    public DuplicationDetector(final AntiDuplicationPlugin plugin, final ConfigManager config,
                               final ItemManager itemManager, final RedisRegistry redisRegistry,
                               final Executor analysisExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.redisRegistry = redisRegistry;
        this.analysisExecutor = analysisExecutor;
        this.scanner = new InventoryScanner(plugin, config, itemManager, itemManager.getStackManager());
    }

    /**
     * Sweeps the whole server and analyses the result.
     *
     * @return the events to apply; an empty list when a sweep is already running
     */
    public CompletableFuture<List<DuplicationEvent>> performFullScan() {
        if (!this.scanInProgress.compareAndSet(false, true)) {
            this.plugin.getLogger().fine("A scan is already running, this one is skipped");
            return CompletableFuture.completedFuture(Collections.<DuplicationEvent>emptyList());
        }

        this.lastScanStartedAt = System.currentTimeMillis();
        return this.scanner.scanServer()
                .thenApplyAsync(snapshot -> this.analyse(snapshot, "full_scan"), this.analysisExecutor)
                .handle((events, throwable) -> {
                    this.scanInProgress.set(false);
                    if (throwable != null) {
                        this.plugin.getLogger().log(Level.SEVERE, "Error during duplication scan", throwable);
                        return Collections.<DuplicationEvent>emptyList();
                    }
                    return events;
                });
    }

    /**
     * Sweeps a single player. Does not take the global scan lock, since it is
     * cheap and triggered by an operator.
     */
    public CompletableFuture<List<DuplicationEvent>> scanPlayer(final Player player) {
        return this.scanner.scanPlayer(player)
                .thenApplyAsync(snapshot -> this.analyse(snapshot, "player_scan"), this.analysisExecutor)
                .exceptionally(throwable -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Error scanning " + player.getName(), throwable);
                    return Collections.emptyList();
                });
    }

    /**
     * Groups the snapshot by UUID and decides, for each UUID seen more than
     * once, whether it is a duplication and which copy to keep.
     */
    private List<DuplicationEvent> analyse(final ScanSnapshot snapshot, final String method) {
        final long startedAt = System.currentTimeMillis();
        final List<DuplicationEvent> events = new ArrayList<>();
        final Set<String> foreignDuplicates = this.collectForeignDuplicates(snapshot);

        for (final Map.Entry<String, List<TrackedItem>> entry : snapshot.getItemsByUuid().entrySet()) {
            final String uuid = entry.getKey();
            final List<TrackedItem> copies = entry.getValue();

            if (copies.size() == 1 && !foreignDuplicates.contains(uuid)) {
                this.itemManager.rememberOwner(uuid, copies.get(0).getItemLocation().getOwnerKey());
                continue;
            }
            if (copies.size() > 1 && this.itemManager.getDivisionTracker().isLegitimate(uuid, copies)) {
                this.plugin.getLogger().fine(String.format(
                        "Legitimate stack division ignored: UUID %s split in %d", uuid, copies.size()));
                continue;
            }
            if (copies.size() == 1) {
                // Seen once here but also on another server of the network.
                this.plugin.getLogger().warning(String.format(
                        "UUID %s is also present on another server of the network", uuid));
                this.itemManager.rememberOwner(uuid, copies.get(0).getItemLocation().getOwnerKey());
                continue;
            }

            events.add(this.buildEvent(uuid, copies, method, snapshot.getDurationMs()));
            this.itemManager.countDetectedDuplication();
        }

        this.lastScanDuration = snapshot.getDurationMs();
        this.lastScanItemCount = snapshot.getItemsScanned();
        this.lastScanDuplicationCount = events.size();

        this.plugin.getLogger().fine(String.format(
                "Scan analysed in %dms: %d items, %d UUIDs assigned, %d duplications, %d oversized stacks%s",
                System.currentTimeMillis() - startedAt, snapshot.getItemsScanned(), snapshot.getUuidsAssigned(),
                events.size(), snapshot.getOversizedStacks(), snapshot.isTruncated() ? " (partial scan)" : ""));
        return events;
    }

    /**
     * Publishes the observed UUIDs to Redis, if enabled, and reports those that
     * another server of the network also holds.
     */
    private Set<String> collectForeignDuplicates(final ScanSnapshot snapshot) {
        if (this.redisRegistry == null || !this.redisRegistry.isAvailable()) {
            return Collections.emptySet();
        }
        try {
            return this.redisRegistry.claim(snapshot.getItemsByUuid().keySet());
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to reach the shared registry", e);
            return Collections.emptySet();
        }
    }

    private DuplicationEvent buildEvent(final String uuid, final List<TrackedItem> copies,
                                        final String method, final long scanDurationMs) {
        final TrackedItem retained = this.chooseRetainedCopy(uuid, copies);
        final Instant firstSeen = this.itemManager.firstSeen(uuid);

        return new DuplicationEvent.Builder()
                .duplicatedUuid(uuid)
                .duplicatedItems(firstSeen == null ? copies : withFirstSeen(copies, firstSeen))
                .retainedItem(firstSeen == null ? retained : retained.withFirstSeen(firstSeen))
                .detectionTime(Instant.now())
                .detectionMethod(method)
                .severity(this.calculateSeverity(copies))
                .scanDurationMs(scanDurationMs)
                .additionalInfo(describeSpread(copies))
                .build();
    }

    private static List<TrackedItem> withFirstSeen(final List<TrackedItem> copies, final Instant firstSeen) {
        final List<TrackedItem> dated = new ArrayList<>(copies.size());
        for (final TrackedItem copy : copies) {
            dated.add(copy.withFirstSeen(firstSeen));
        }
        return dated;
    }

    /**
     * Picks the copy that stays. The holder the UUID was last seen at wins,
     * which keeps the original in place and deletes what appeared elsewhere. With
     * no history, a stable ordering is used so the decision does not depend on
     * iteration order.
     */
    private TrackedItem chooseRetainedCopy(final String uuid, final List<TrackedItem> copies) {
        final String knownOwner = this.itemManager.knownOwner(uuid);
        if (knownOwner != null) {
            for (final TrackedItem copy : copies) {
                if (knownOwner.equals(copy.getItemLocation().getOwnerKey())) {
                    return copy;
                }
            }
        }
        return Collections.min(copies, RETENTION_ORDER);
    }

    /**
     * Deterministic fallback ordering: prefer a copy held by a player over one
     * lying in a container or on the ground, then order by holder and slot.
     */
    private static final Comparator<TrackedItem> RETENTION_ORDER = new Comparator<TrackedItem>() {
        @Override
        public int compare(final TrackedItem left, final TrackedItem right) {
            final ItemLocation leftLocation = left.getItemLocation();
            final ItemLocation rightLocation = right.getItemLocation();
            final int byKind = Integer.compare(leftLocation.getKind().ordinal(), rightLocation.getKind().ordinal());
            if (byKind != 0) {
                return byKind;
            }
            final int byOwner = leftLocation.getOwnerKey().compareTo(rightLocation.getOwnerKey());
            return byOwner != 0 ? byOwner : Integer.compare(leftLocation.getSlot(), rightLocation.getSlot());
        }
    };

    private DuplicationEvent.Severity calculateSeverity(final List<TrackedItem> copies) {
        final int count = copies.size();
        boolean valuable = false;
        for (final TrackedItem copy : copies) {
            if (this.itemManager.getStackManager().isValuable(copy.getMaterial())) {
                valuable = true;
                break;
            }
        }

        if (count >= 10 || (count >= 5 && valuable)) {
            return DuplicationEvent.Severity.CRITICAL;
        }
        if (count >= 5 || (count >= 3 && valuable)) {
            return DuplicationEvent.Severity.HIGH;
        }
        return count >= 3 ? DuplicationEvent.Severity.MEDIUM : DuplicationEvent.Severity.LOW;
    }

    /**
     * Short summary of how far a duplication has spread, which is the first thing
     * an administrator wants to know.
     */
    private static String describeSpread(final List<TrackedItem> copies) {
        final Set<String> players = new HashSet<>();
        final Set<String> containers = new HashSet<>();
        for (final TrackedItem copy : copies) {
            if (copy.getPlayerName() != null) {
                players.add(copy.getPlayerName());
            } else {
                containers.add(copy.getContainer());
            }
        }

        final StringBuilder info = new StringBuilder();
        if (players.size() == 1) {
            info.append("All copies held by ").append(players.iterator().next()).append(". ");
        } else if (players.size() > 1) {
            info.append("Copies spread across ").append(players.size()).append(" players. ");
        }
        if (!containers.isEmpty()) {
            info.append("Also present in ").append(containers).append('.');
        }
        return info.toString().trim();
    }

    /**
     * @return {@code true} when the previous scan is recent enough that another
     *         one would be wasted effort
     */
    public boolean isRecentScanAvailable() {
        return System.currentTimeMillis() - this.lastScanStartedAt < this.config.getScanIntervalMillis() / 2;
    }

    public boolean isScanInProgress() {
        return this.scanInProgress.get();
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
        return String.format("Last scan: %dms, %d items, %d duplications",
                this.lastScanDuration, this.lastScanItemCount, this.lastScanDuplicationCount);
    }
}
