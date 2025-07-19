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
import org.bukkit.Bukkit;

import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class ThreadManager {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;
    private final DuplicationDetector detector;

    // Thread pool for asynchronous tasks
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> scanTask;
    private ScheduledFuture<?> cleanupTask;
    private volatile boolean running = false;

    public ThreadManager(final AntiDuplicationPlugin plugin, final ConfigManager config, final ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.detector = new DuplicationDetector(plugin, config, itemManager);

        // Creating thread pools
        this.executorService = Executors.newFixedThreadPool(
                config.getThreadPoolSize(),
                r -> {
                    final Thread t = new Thread(r, "AntiDupe-Worker");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
                    return t;
                }
        );

        this.scheduledExecutor = Executors.newScheduledThreadPool(
                2, // 2 threads for scheduled tasks
                r -> {
                    final Thread t = new Thread(r, "AntiDupe-Scheduler");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
        );

        plugin.getLogger().info("ThreadManager initialized with " + config.getThreadPoolSize() + " worker threads");
    }

    public void startTasks() {
        if (this.running) {
            this.plugin.getLogger().warning("ThreadManager already running");
            return;
        }
        this.running = true;
        this.startScanTask();
        this.startCleanupTask();
        this.plugin.getLogger().info("Asynchronous tasks started");
    }

    private void startScanTask() {
        this.scanTask = this.scheduledExecutor.scheduleAtFixedRate(
                new ScannerTask(this.plugin, this.detector, this.itemManager),
                10,
                this.config.getScanInterval(),
                TimeUnit.SECONDS
        );
        this.plugin.getLogger().info("Scan task scheduled every " + this.config.getScanInterval() + " seconds");
    }

    private void startCleanupTask() {
        this.cleanupTask = this.scheduledExecutor.scheduleAtFixedRate(
                this::performCleanup,
                60, // Initial cleaning after 1 minute
                300, // Then every 5 minutes
                TimeUnit.SECONDS
        );
        this.plugin.getLogger().fine("Cleanup task scheduled every 5 minutes");
    }

    public void scheduleInitialScan() {
        this.executorService.submit(() -> {
            try {
                Thread.sleep(5000);
                this.plugin.getLogger().info("Starting initial scan of existing items...");
                this.itemManager.initializeExistingItems();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error during initial scan", e);
            }
        });
    }

    public CompletableFuture<ScanResult> performManualScan() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final long startTime = System.currentTimeMillis();
                final List<DuplicationEvent> duplications = this.detector.performFullScan().get(30, TimeUnit.SECONDS);
                if (!duplications.isEmpty()) {
                    final CompletableFuture<Void> processingFuture = CompletableFuture.runAsync(() -> {
                        // This part must be executed in the main thread
                        Bukkit.getScheduler().runTask(this.plugin, () -> {
                            this.itemManager.processDuplicationEvents(duplications);
                        });
                    });
                    // Wait until treatment is complete
                    processingFuture.get(10, TimeUnit.SECONDS);
                }
                final long duration = System.currentTimeMillis() - startTime;
                return new ScanResult(
                        duplications.size(),
                        this.detector.getLastScanItemCount(),
                        duration,
                        true,
                        null
                );
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error during manual scan", e);
                return new ScanResult(0, 0, 0, false, e.getMessage());
            }
        }, this.executorService);
    }

    private void performCleanup() {
        try {
            this.itemManager.cleanupOldItems();
            if (this.plugin.getJsonLogger() != null) {
                this.plugin.getJsonLogger().cleanupOldLogs();
            }
            this.plugin.getLogger().finest("Periodic cleanup performed");
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during cleanup", e);
        }
    }

    public void updateConfig(final ConfigManager newConfig) {
        try {
            this.stopTasks();
            this.startTasks();
            this.plugin.getLogger().info("ThreadManager configuration updated");
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error updating configuration", e);
        }
    }

    private void stopTasks() {
        if (this.scanTask != null && !this.scanTask.isCancelled()) {
            this.scanTask.cancel(false);
        }
        if (this.cleanupTask != null && !this.cleanupTask.isCancelled()) {
            this.cleanupTask.cancel(false);
        }
    }

    public void shutdown() {
        this.running = false;
        this.plugin.getLogger().info("Shutting down ThreadManager...");
        this.stopTasks();
        this.shutdownExecutor(this.scheduledExecutor, "ScheduledExecutor");
        this.shutdownExecutor(this.executorService, "ExecutorService");
        this.plugin.getLogger().info("ThreadManager stopped");
    }

    private void shutdownExecutor(final ExecutorService executor, final String name) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.plugin.getLogger().warning(name + " did not terminate in time, forcing shutdown");
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.plugin.getLogger().severe(name + " could not be stopped");
                }
            }
        } catch (final InterruptedException e) {
            this.plugin.getLogger().warning("Interrupted during shutdown of " + name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public <T> CompletableFuture<T> submitTask(final Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }, this.executorService);
    }

    public void runOnMainThread(final Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    public DuplicationDetector getDetector() {
        return this.detector;
    }

    public boolean isRunning() {
        return this.running;
    }

    public String getThreadStats() {
        final ThreadPoolExecutor tpe = (ThreadPoolExecutor) this.executorService;
        return String.format(
                "ThreadManager Stats - Workers: %d/%d (active/total), Queue: %d tasks, Completed: %d",
                tpe.getActiveCount(),
                tpe.getPoolSize(),
                tpe.getQueue().size(),
                tpe.getCompletedTaskCount()
        );
    }

    public static final class ScanResult {
        private final int duplicationsFound;
        private final int itemsScanned;
        private final long duration;
        private final boolean success;
        private final String errorMessage;

        public ScanResult(final int duplicationsFound, final int itemsScanned, final long duration, final boolean success, final String errorMessage) {
            this.duplicationsFound = duplicationsFound;
            this.itemsScanned = itemsScanned;
            this.duration = duration;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public int getDuplicationsFound() {
            return this.duplicationsFound;
        }

        public int getItemsScanned() {
            return this.itemsScanned;
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
            if (this.success) {
                return String.format("Scan completed: %d duplications found, %d items scanned in %dms",
                        this.duplicationsFound, this.itemsScanned, this.duration);
            } else {
                return "Scan failed: " + this.errorMessage;
            }
        }
    }
}