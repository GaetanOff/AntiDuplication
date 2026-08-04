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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only result of one inventory sweep.
 *
 * <p>Built on the main thread and then handed to the analysis threads, which is
 * only safe because it holds plain values instead of live item stacks.
 */
public final class ScanSnapshot {

    private final Map<String, List<TrackedItem>> itemsByUuid;
    private final int itemsScanned;
    private final int uuidsAssigned;
    private final int oversizedStacks;
    private final int untaggedRejected;
    private final long durationMs;
    private final boolean truncated;

    private ScanSnapshot(final Builder builder) {
        this.itemsByUuid = Collections.unmodifiableMap(builder.itemsByUuid);
        this.itemsScanned = builder.itemsScanned;
        this.uuidsAssigned = builder.uuidsAssigned;
        this.oversizedStacks = builder.oversizedStacks;
        this.untaggedRejected = builder.untaggedRejected;
        this.durationMs = builder.durationMs;
        this.truncated = builder.truncated;
    }

    /**
     * @return every observed copy grouped by UUID; more than one copy under a key
     *         is a duplication candidate
     */
    public Map<String, List<TrackedItem>> getItemsByUuid() {
        return this.itemsByUuid;
    }

    public int getItemsScanned() {
        return this.itemsScanned;
    }

    public int getUuidsAssigned() {
        return this.uuidsAssigned;
    }

    /**
     * @return stacks found above the legal size, trimmed back when the strict
     *         mode is on
     */
    public int getOversizedStacks() {
        return this.oversizedStacks;
    }

    /**
     * @return trackable items deleted for carrying no UUID, which only happens
     *         with {@code security.block-no-uuid} enabled
     */
    public int getUntaggedRejected() {
        return this.untaggedRejected;
    }

    public long getDurationMs() {
        return this.durationMs;
    }

    /**
     * @return {@code true} when the sweep hit its time budget and covers only
     *         part of the server
     */
    public boolean isTruncated() {
        return this.truncated;
    }

    public static ScanSnapshot empty() {
        return new Builder().build();
    }

    static final class Builder {
        private final Map<String, List<TrackedItem>> itemsByUuid = new HashMap<>();
        private int itemsScanned;
        private int uuidsAssigned;
        private int oversizedStacks;
        private int untaggedRejected;
        private long durationMs;
        private boolean truncated;

        void add(final TrackedItem item) {
            List<TrackedItem> copies = this.itemsByUuid.get(item.getUuid());
            if (copies == null) {
                // Nearly every UUID is unique: start with the smallest useful list.
                copies = new ArrayList<>(1);
                this.itemsByUuid.put(item.getUuid(), copies);
            }
            copies.add(item);
            this.itemsScanned++;
        }

        void countAssignedUuid() {
            this.uuidsAssigned++;
        }

        void countOversizedStack() {
            this.oversizedStacks++;
        }

        void countUntaggedRejected() {
            this.untaggedRejected++;
        }

        void markTruncated() {
            this.truncated = true;
        }

        Builder durationMs(final long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        ScanSnapshot build() {
            return new ScanSnapshot(this);
        }
    }
}
