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

package fr.gaetandev.antiduplication.threading;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import fr.gaetandev.antiduplication.core.DuplicationDetector;
import fr.gaetandev.antiduplication.core.ItemManager;
import fr.gaetandev.antiduplication.model.DuplicationEvent;
import fr.gaetandev.antiduplication.storage.RedisRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Owns the plugin's threads and orchestrates the scan pipeline.
 *
 * <p>The pipeline alternates threads on purpose: the inventory sweep must run on
 * the main thread (item stacks are not thread safe), the analysis is pure
 * computation and runs on a worker, and applying removals goes back to the main
 * thread. Periodic triggers use the Bukkit scheduler rather than a private
 * timer, so the server cancels them automatically when the plugin unloads.
 */
public final class ThreadManager {

    /** How long to wait for the worker pool to drain on shutdown. */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    /** Delay before the first scan, giving the server time to finish booting. */
    private static final long INITIAL_SCAN_DELAY_SECONDS = 10L;

    /** Housekeeping cadence, unrelated to the scan interval. */
    private static final long CLEANUP_PERIOD_SECONDS = 300L;

    private static final long TICKS_PER_SECOND = 20L;

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;
    private final RedisRegistry redisRegistry;
    private final DuplicationDetector detector;
    private final ScannerTask scannerTask;
    private final ExecutorService executorService;

    private BukkitTask scanTask;
    private BukkitTask cleanupTask;
    private volatile boolean running;
    private volatile boolean initialized;

    public ThreadManager(final AntiDuplicationPlugin plugin, final ConfigManager config,
                         final ItemManager itemManager, final RedisRegistry redisRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.redisRegistry = redisRegistry;
        this.detector = new DuplicationDetector(plugin, config, itemManager, redisRegistry);
        this.scannerTask = new ScannerTask(plugin, this);

        final AtomicInteger workerNumber = new AtomicInteger();
        this.executorService = Executors.newFixedThreadPool(config.getThreadPoolSize(), runnable -> {
            final Thread thread = new Thread(runnable, "AntiDupe-Worker-" + workerNumber.incrementAndGet());
            thread.setDaemon(true);
            // Never compete with the server thread for CPU.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });

        plugin.getLogger().info("ThreadManager created with " + config.getThreadPoolSize() + " worker threads");
    }

    public void initialize() {
        if (this.initialized) {
            this.plugin.getLogger().warning("ThreadManager already initialized");
            return;
        }
        this.initialized = true;
    }

    public void startTasks() {
        if (!this.initialized) {
            this.plugin.getLogger().severe("Cannot start tasks: ThreadManager is not initialized");
            return;
        }
        if (this.running) {
            this.plugin.getLogger().warning("ThreadManager tasks are already running");
            return;
        }

        this.running = true;
        this.scanTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this.scannerTask,
                INITIAL_SCAN_DELAY_SECONDS * TICKS_PER_SECOND,
                this.config.getScanInterval() * TICKS_PER_SECOND);
        this.cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::performCleanup,
                CLEANUP_PERIOD_SECONDS * TICKS_PER_SECOND,
                CLEANUP_PERIOD_SECONDS * TICKS_PER_SECOND);

        this.plugin.getLogger().info("Scan scheduled every " + this.config.getScanInterval()
                + "s, housekeeping every " + CLEANUP_PERIOD_SECONDS + "s");
    }

    /**
     * Cancels the periodic tasks. Clearing the running flag is what allows
     * {@link #startTasks()} to schedule them again after a reload.
     */
    public void stopTasks() {
        if (this.scanTask != null) {
            this.scanTask.cancel();
            this.scanTask = null;
        }
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        this.running = false;
    }

    /**
     * Re-reads the schedule after a configuration reload.
     */
    public void updateConfig() {
        try {
            this.stopTasks();
            this.startTasks();
            this.plugin.getLogger().info("Scan schedule updated from the new configuration");
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error applying the new scan schedule", e);
        }
    }

    /**
     * Runs the whole pipeline once: sweep, analyse, then remove.
     */
    public CompletableFuture<ScanResult> runScan(final String trigger) {
        final long startTime = System.currentTimeMillis();
        return this.detector.performFullScan()
                .thenCompose(this::applyEvents)
                .handle((removed, throwable) -> {
                    final long duration = System.currentTimeMillis() - startTime;
                    if (throwable != null) {
                        this.plugin.getLogger().log(Level.SEVERE, "Scan failed (" + trigger + ")", throwable);
                        return ScanResult.failure(throwable.getMessage());
                    }
                    return new ScanResult(
                            this.detector.getLastScanDuplicationCount(),
                            this.detector.getLastScanItemCount(),
                            removed,
                            duration);
                });
    }

    /**
     * Sweeps a single player and removes what it finds.
     */
    public CompletableFuture<ScanResult> runPlayerScan(final Player player) {
        final long startTime = System.currentTimeMillis();
        return this.detector.scanPlayer(player)
                .thenCompose(events -> this.applyEvents(events).thenApply(removed ->
                        new ScanResult(events.size(), this.detector.getLastScanItemCount(), removed,
                                System.currentTimeMillis() - startTime)))
                .exceptionally(throwable -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Player scan failed", throwable);
                    return ScanResult.failure(throwable.getMessage());
                });
    }

    /**
     * Hands the events back to the main thread, which is the only place items may
     * be deleted.
     *
     * @return the number of copies actually removed
     */
    private CompletableFuture<Integer> applyEvents(final List<DuplicationEvent> events) {
        if (events == null || events.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        final CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    future.complete(this.itemManager.applyDuplicationEvents(events));
                } catch (final RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (final RuntimeException e) {
            // Bukkit refuses new tasks once the plugin is being disabled.
            this.plugin.getLogger().warning("Duplication removal skipped, the plugin is shutting down");
            future.complete(0);
        }
        return future;
    }

    private void performCleanup() {
        try {
            this.itemManager.cleanupOldItems();
            if (this.plugin.getJsonLogger() != null) {
                this.plugin.getJsonLogger().cleanupOldLogs();
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during housekeeping", e);
        }
    }

    /**
     * Submits work to the plugin's worker pool.
     */
    public <T> CompletableFuture<T> submitTask(final Callable<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            this.executorService.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (final Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (final RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void runOnMainThread(final Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    public void shutdown() {
        this.plugin.getLogger().info("Shutting down ThreadManager...");
        this.stopTasks();
        this.shutdownExecutor();
        this.plugin.getLogger().info("ThreadManager stopped");
    }

    private void shutdownExecutor() {
        if (this.executorService.isShutdown()) {
            return;
        }
        this.executorService.shutdown();
        try {
            if (!this.executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                this.plugin.getLogger().warning("Worker pool did not drain in time, forcing shutdown");
                this.executorService.shutdownNow();
            }
        } catch (final InterruptedException e) {
            this.executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public DuplicationDetector getDetector() {
        return this.detector;
    }

    public ScannerTask getScannerTask() {
        return this.scannerTask;
    }

    public RedisRegistry getRedisRegistry() {
        return this.redisRegistry;
    }

    public boolean isRunning() {
        return this.running;
    }

    public String getThreadStats() {
        if (!(this.executorService instanceof ThreadPoolExecutor)) {
            return "Workers: unavailable";
        }
        final ThreadPoolExecutor pool = (ThreadPoolExecutor) this.executorService;
        return String.format("Workers %d/%d active, queue %d, completed %d",
                pool.getActiveCount(), pool.getPoolSize(), pool.getQueue().size(), pool.getCompletedTaskCount());
    }

    /**
     * Outcome of one full pipeline run.
     */
    public static final class ScanResult {
        private final int duplicationsFound;
        private final int itemsScanned;
        private final int copiesRemoved;
        private final long duration;
        private final boolean success;
        private final String errorMessage;

        public ScanResult(final int duplicationsFound, final int itemsScanned,
                          final int copiesRemoved, final long duration) {
            this(duplicationsFound, itemsScanned, copiesRemoved, duration, true, null);
        }

        private ScanResult(final int duplicationsFound, final int itemsScanned, final int copiesRemoved,
                           final long duration, final boolean success, final String errorMessage) {
            this.duplicationsFound = duplicationsFound;
            this.itemsScanned = itemsScanned;
            this.copiesRemoved = copiesRemoved;
            this.duration = duration;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        static ScanResult failure(final String errorMessage) {
            return new ScanResult(0, 0, 0, 0, false, errorMessage);
        }

        public int getDuplicationsFound() {
            return this.duplicationsFound;
        }

        public int getItemsScanned() {
            return this.itemsScanned;
        }

        public int getCopiesRemoved() {
            return this.copiesRemoved;
        }

        public long getDuration() {
            return this.duration;
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getErrorMessage() {
            return this.errorMessage;
        }

        @Override
        public String toString() {
            return this.success
                    ? String.format("Scan completed: %d duplications, %d copies removed, %d items scanned in %dms",
                    this.duplicationsFound, this.copiesRemoved, this.itemsScanned, this.duration)
                    : "Scan failed: " + this.errorMessage;
        }
    }
}
