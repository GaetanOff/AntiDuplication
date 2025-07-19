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
import fr.gaetandev.antiduplication.core.DuplicationDetector;
import fr.gaetandev.antiduplication.core.ItemManager;
import fr.gaetandev.antiduplication.model.DuplicationEvent;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class ScannerTask implements Runnable {

    private final AntiDuplicationPlugin plugin;
    private final DuplicationDetector detector;
    private final ItemManager itemManager;
    private volatile long lastRunTime = 0;
    private volatile int totalScansPerformed = 0;
    private volatile int totalDuplicationsFound = 0;

    public ScannerTask(final AntiDuplicationPlugin plugin, final DuplicationDetector detector, final ItemManager itemManager) {
        this.plugin = plugin;
        this.detector = detector;
        this.itemManager = itemManager;
    }

    @Override
    public void run() {
        if (!this.plugin.isPluginInitialized()) {
            this.plugin.getLogger().fine("Plugin not initialized, scan postponed");
            return;
        }

        final long startTime = System.currentTimeMillis();
        try {
            this.performScan().whenComplete((result, exception) -> {
                this.lastRunTime = System.currentTimeMillis() - startTime;
                this.totalScansPerformed++;
                if (exception != null) {
                    this.plugin.getLogger().log(Level.WARNING, "Error during automatic scan", exception);
                } else {
                    if (result > 0) {
                        this.totalDuplicationsFound += result;
                        this.plugin.getLogger().info("Scan completed: " + result + " duplications detected");
                    } else {
                        this.plugin.getLogger().fine("Scan completed: no duplications detected");
                    }
                }
            });
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Critical error in ScannerTask", e);
        }
    }

    private CompletableFuture<Integer> performScan() {
        return this.detector.performFullScan()
                .thenCompose(duplications -> {
                    if (duplications.isEmpty()) {
                        return CompletableFuture.completedFuture(0);
                    }
                    // Handling duplications in the main thread
                    return this.processDuplicationsInMainThread(duplications)
                            .thenApply(processed -> duplications.size());
                })
                .exceptionally(throwable -> {
                    this.plugin.getLogger().log(Level.WARNING, "Error during scan", throwable);
                    return 0;
                });
    }

    private CompletableFuture<Void> processDuplicationsInMainThread(final List<DuplicationEvent> duplications) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                this.itemManager.processDuplicationEvents(duplications);
                future.complete(null);
            } catch (final Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private boolean canPerformScan() {
        if (this.getServerLoad() > 80.0) {
            this.plugin.getLogger().fine("High server load, scan postponed");
            return false;
        }
        if (this.detector.isRecentScanAvailable()) {
            this.plugin.getLogger().finest("Recent scan available, skipping");
            return false;
        }
        return true;
    }

    private double getServerLoad() {
        try {
            final Runtime runtime = Runtime.getRuntime();
            final long totalMemory = runtime.totalMemory();
            final long freeMemory = runtime.freeMemory();
            final long usedMemory = totalMemory - freeMemory;
            return ((double) usedMemory / totalMemory) * 100.0;
        } catch (final Exception e) {
            return 50.0;
        }
    }

    public long getLastRunTime() {
        return this.lastRunTime;
    }

    public int getTotalScansPerformed() {
        return this.totalScansPerformed;
    }

    public int getTotalDuplicationsFound() {
        return this.totalDuplicationsFound;
    }

    public String getPerformanceReport() {
        return String.format(
                "ScannerTask Stats - Scans: %d, Duplications: %d, Last time: %dms",
                this.totalScansPerformed, this.totalDuplicationsFound, this.lastRunTime
        );
    }

    public void resetStats() {
        this.totalScansPerformed = 0;
        this.totalDuplicationsFound = 0;
        this.lastRunTime = 0;
    }
}
