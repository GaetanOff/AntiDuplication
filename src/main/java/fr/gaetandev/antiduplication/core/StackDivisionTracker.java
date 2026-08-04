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

import fr.gaetandev.antiduplication.model.TrackedItem;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Remembers stack splits so that the halves of a split stack are not reported as
 * a duplication.
 *
 * <p>Splitting a stack copies its item data, UUID included, so both halves share
 * one identifier. That is legitimate as long as the pieces add up to no more
 * than the stack that was split, they appear shortly after the split, and the
 * stack was not cut into more pieces than the configuration allows.
 *
 * <p>Only relevant when stackable item tracking is enabled; with the default
 * configuration no tracked item can be split in the first place.
 */
public final class StackDivisionTracker {

    /** Hard ceiling so a flood of splits cannot grow the map without bound. */
    private static final int MAX_ENTRIES = 20_000;

    /**
     * Read through suppliers rather than captured values so a configuration
     * reload takes effect without rebuilding the tracker.
     */
    private final LongSupplier toleranceMillis;
    private final IntSupplier maxDivisionsPerStack;

    private final Map<String, Division> divisions = new ConcurrentHashMap<>();

    public StackDivisionTracker(final LongSupplier toleranceMillis, final IntSupplier maxDivisionsPerStack) {
        this.toleranceMillis = toleranceMillis;
        this.maxDivisionsPerStack = maxDivisionsPerStack;
    }

    /**
     * Records that {@code uuid} legitimately covers up to {@code totalAmount}
     * items spread over several stacks.
     */
    public void register(final String uuid, final int totalAmount) {
        if (uuid == null) {
            return;
        }
        if (this.divisions.size() >= MAX_ENTRIES) {
            this.evictExpired();
            if (this.divisions.size() >= MAX_ENTRIES) {
                return;
            }
        }
        this.divisions.merge(uuid, new Division(totalAmount),
                (existing, fresh) -> new Division(Math.max(existing.allowedTotal, fresh.allowedTotal)));
    }

    /**
     * @return {@code true} when the copies sharing {@code uuid} are the pieces of
     *         a recent, in-budget split rather than a duplication
     */
    public boolean isLegitimate(final String uuid, final Collection<TrackedItem> copies) {
        final Division division = this.divisions.get(uuid);
        if (division == null) {
            return false;
        }
        if (division.isExpired(this.toleranceMillis.getAsLong())) {
            this.divisions.remove(uuid);
            return false;
        }
        if (copies.size() > this.maxDivisionsPerStack.getAsInt()) {
            return false;
        }

        int currentTotal = 0;
        for (final TrackedItem copy : copies) {
            currentTotal += copy.getAmount();
        }
        return currentTotal <= division.allowedTotal;
    }

    public void forget(final String uuid) {
        this.divisions.remove(uuid);
    }

    /**
     * Drops entries that can no longer authorise anything.
     */
    public int evictExpired() {
        final long tolerance = this.toleranceMillis.getAsLong();
        final int before = this.divisions.size();
        this.divisions.values().removeIf(division -> division.isExpired(tolerance));
        return before - this.divisions.size();
    }

    public int size() {
        return this.divisions.size();
    }

    private static final class Division {
        private final int allowedTotal;
        private final long registeredAt;

        Division(final int allowedTotal) {
            this.allowedTotal = allowedTotal;
            this.registeredAt = System.currentTimeMillis();
        }

        /**
         * The window covers ages in {@code [0, tolerance)}, so a tolerance of
         * zero authorises nothing at all.
         */
        boolean isExpired(final long toleranceMillis) {
            return System.currentTimeMillis() - this.registeredAt >= toleranceMillis;
        }
    }
}
