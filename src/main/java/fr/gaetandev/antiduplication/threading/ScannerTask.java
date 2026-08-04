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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Periodic trigger for the scan pipeline.
 *
 * <p>Runs on the main thread and returns immediately: it only starts the
 * pipeline, which slices its own main-thread work across ticks. Overlapping runs
 * are skipped rather than queued, so a scan that outlives its interval on a busy
 * server cannot pile up.
 */
public final class ScannerTask implements Runnable {

    private final AntiDuplicationPlugin plugin;
    private final ThreadManager threadManager;

    private final AtomicInteger scansPerformed = new AtomicInteger();
    private final AtomicInteger scansSkipped = new AtomicInteger();
    private final AtomicInteger duplicationsFound = new AtomicInteger();
    private final AtomicInteger copiesRemoved = new AtomicInteger();
    private final AtomicLong lastRunTime = new AtomicLong();

    public ScannerTask(final AntiDuplicationPlugin plugin, final ThreadManager threadManager) {
        this.plugin = plugin;
        this.threadManager = threadManager;
    }

    @Override
    public void run() {
        if (!this.plugin.isPluginInitialized()) {
            this.plugin.debug("Plugin not initialized yet, scan postponed");
            return;
        }
        if (!this.canPerformScan()) {
            this.scansSkipped.incrementAndGet();
            return;
        }

        try {
            this.threadManager.runScan("scheduled_scan").whenComplete((result, throwable) -> {
                this.scansPerformed.incrementAndGet();
                if (throwable != null) {
                    this.plugin.getLogger().log(Level.WARNING, "Error during the scheduled scan", throwable);
                    return;
                }
                this.lastRunTime.set(result.getDuration());
                if (!result.isSuccess()) {
                    this.plugin.getLogger().warning("Scheduled scan failed: " + result.getErrorMessage());
                    return;
                }
                this.duplicationsFound.addAndGet(result.getDuplicationsFound());
                this.copiesRemoved.addAndGet(result.getCopiesRemoved());
                if (result.getDuplicationsFound() > 0) {
                    this.plugin.getLogger().info(result.toString());
                } else {
                    this.plugin.debug(result.toString());
                }
            });
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Critical error in ScannerTask", e);
        }
    }

    /**
     * A sweep already in flight is the only reason to stand down: it holds the
     * main-thread budget this run would compete for.
     */
    private boolean canPerformScan() {
        if (this.threadManager.getDetector().isScanInProgress()) {
            this.plugin.debug("Previous scan still running, this one is skipped");
            return false;
        }
        return true;
    }

    public String getPerformanceReport() {
        return String.format("Scans: %d (%d skipped), duplications: %d, copies removed: %d, last run: %dms",
                this.scansPerformed.get(), this.scansSkipped.get(),
                this.duplicationsFound.get(), this.copiesRemoved.get(), this.lastRunTime.get());
    }
}
