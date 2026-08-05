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
import fr.gaetandev.antiduplication.storage.RedisRegistry;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

    private volatile long lastScanDuration;
    private volatile int lastScanItemCount;
    private volatile int lastScanDuplicationCount;

    public DuplicationDetector(final AntiDuplicationPlugin plugin, final ConfigManager config,
                               final ItemManager itemManager, final RedisRegistry redisRegistry,
                               final Executor analysisExecutor) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.redisRegistry = redisRegistry;
        this.analysisExecutor = analysisExecutor;
        this.scanner = new InventoryScanner(plugin, config, itemManager, itemManager.getStackManager());
    }

    /**
     * Sweeps the server and analyses the result.
     *
     * @param includeContainers whether to walk the loaded chunks too, or only the
     *                          online players
     * @return the events to apply; an empty list when a sweep is already running
     */
    public CompletableFuture<List<DuplicationEvent>> performFullScan(final boolean includeContainers) {
        if (!this.scanInProgress.compareAndSet(false, true)) {
            this.plugin.debug("A scan is already running, this one is skipped");
            return CompletableFuture.completedFuture(Collections.<DuplicationEvent>emptyList());
        }

        final String method = includeContainers ? "full_scan" : "player_sweep";
        return this.scanner.scanServer(includeContainers)
                .thenApplyAsync(snapshot -> this.analyse(snapshot, method), this.analysisExecutor)
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

            if (copies.size() > 1) {
                if (this.itemManager.getDivisionTracker().isLegitimate(uuid, copies)) {
                    this.plugin.debug(String.format(
                            "Legitimate stack division ignored: UUID %s split in %d", uuid, copies.size()));
                    continue;
                }
                events.add(this.buildEvent(uuid, copies, method, snapshot.getDurationMs()));
                this.itemManager.countDetectedDuplication();
                continue;
            }

            // A single local copy is normal, unless another server of the network
            // reports holding the same UUID. Nothing can be removed in that case:
            // the other copy is out of reach, so it is reported for an operator.
            if (foreignDuplicates.contains(uuid)) {
                this.reportForeignDuplicate(uuid, copies.get(0));
            }
            this.itemManager.rememberOwner(uuid, copies.get(0).getItemLocation().getOwnerKey());
        }

        this.lastScanDuration = snapshot.getDurationMs();
        this.lastScanItemCount = snapshot.getItemsScanned();
        this.lastScanDuplicationCount = events.size();

        this.plugin.debug(String.format(
                "Scan analysed in %dms: %d items, %d UUIDs assigned, %d duplications, "
                        + "%d oversized stacks, %d untagged rejected%s",
                System.currentTimeMillis() - startedAt, snapshot.getItemsScanned(), snapshot.getUuidsAssigned(),
                events.size(), snapshot.getOversizedStacks(), snapshot.getUntaggedRejected(),
                snapshot.isTruncated() ? " (partial scan)" : ""));
        return events;
    }

    private void reportForeignDuplicate(final String uuid, final TrackedItem localCopy) {
        this.plugin.getLogger().warning(String.format(
                "UUID %s is also held by another server of the network: %s at %s",
                uuid, localCopy.getItemDescription(), localCopy.getLocationDescription()));
        this.plugin.getJsonLogger().logEvent("cross_server_duplication", String.format(
                "UUID: %s, Item: %s, Location: %s, Server: %s",
                uuid, localCopy.getItemDescription(), localCopy.getLocationDescription(),
                this.redisRegistry.getServerId()));
        this.plugin.notifyAdmins(String.format(
                "§c[AntiDupe] %s also exists on another server (%s)",
                localCopy.getItemDescription(), uuid));
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
        final TrackedItem retained = RetentionPolicy.chooseRetained(this.itemManager.knownOwner(uuid), copies);
        final Instant firstSeen = this.itemManager.firstSeen(uuid);

        return new DuplicationEvent.Builder()
                .duplicatedUuid(uuid)
                .duplicatedItems(firstSeen == null ? copies : withFirstSeen(copies, firstSeen))
                .retainedItem(firstSeen == null ? retained : retained.withFirstSeen(firstSeen))
                .detectionTime(Instant.now())
                .detectionMethod(method)
                .severity(RetentionPolicy.severityOf(copies.size(), this.holdsValuableItem(copies)))
                .scanDurationMs(scanDurationMs)
                .additionalInfo(RetentionPolicy.describeSpread(copies))
                .build();
    }

    private static List<TrackedItem> withFirstSeen(final List<TrackedItem> copies, final Instant firstSeen) {
        final List<TrackedItem> dated = new ArrayList<>(copies.size());
        for (final TrackedItem copy : copies) {
            dated.add(copy.withFirstSeen(firstSeen));
        }
        return dated;
    }

    private boolean holdsValuableItem(final List<TrackedItem> copies) {
        for (final TrackedItem copy : copies) {
            if (this.itemManager.getStackManager().isValuable(copy.getMaterial())) {
                return true;
            }
        }
        return false;
    }

    public boolean isScanInProgress() {
        return this.scanInProgress.get();
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
