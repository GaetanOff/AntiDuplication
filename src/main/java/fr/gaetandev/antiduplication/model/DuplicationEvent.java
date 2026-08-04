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

package fr.gaetandev.antiduplication.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One UUID found in several places at once, together with the copy the plugin
 * decided to keep.
 */
public final class DuplicationEvent {

    /**
     * What the plugin ended up doing about a duplication. Decided after the
     * removal pass, not when the event is created, so the audit trail cannot
     * claim a removal that did not happen.
     */
    public enum Action {
        ITEMS_REMOVED("Duplicated copies removed"),
        WARNING_LOGGED("Warning logged, no copy could be removed");

        private final String description;

        Action(final String description) {
            this.description = description;
        }

        public String getDescription() {
            return this.description;
        }
    }

    public enum Severity {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        CRITICAL("Critical");

        private final String description;

        Severity(final String description) {
            this.description = description;
        }

        public String getDescription() {
            return this.description;
        }
    }

    private final String duplicatedUuid;
    private final List<TrackedItem> duplicatedItems;
    private final TrackedItem retainedItem;
    private final Instant detectionTime;
    private final Severity severity;
    private final String detectionMethod;
    private final long scanDurationMs;
    private final String additionalInfo;

    private DuplicationEvent(final Builder builder) {
        this.duplicatedUuid = builder.duplicatedUuid;
        this.duplicatedItems = Collections.unmodifiableList(new ArrayList<>(builder.duplicatedItems));
        this.retainedItem = builder.retainedItem;
        this.detectionTime = builder.detectionTime;
        this.severity = builder.severity;
        this.detectionMethod = builder.detectionMethod;
        this.scanDurationMs = builder.scanDurationMs;
        this.additionalInfo = builder.additionalInfo;
    }

    public String getDuplicatedUuid() {
        return this.duplicatedUuid;
    }

    /**
     * @return every observed copy, the retained one included
     */
    public List<TrackedItem> getDuplicatedItems() {
        return this.duplicatedItems;
    }

    /**
     * @return the copy considered legitimate, which must never be deleted
     */
    public TrackedItem getRetainedItem() {
        return this.retainedItem;
    }

    /**
     * @return the copies to delete, that is every copy but the retained one
     */
    public List<TrackedItem> getItemsToRemove() {
        final List<TrackedItem> toRemove = new ArrayList<>(this.duplicatedItems.size() - 1);
        for (final TrackedItem item : this.duplicatedItems) {
            if (!item.equals(this.retainedItem)) {
                toRemove.add(item);
            }
        }
        return toRemove;
    }

    public Instant getDetectionTime() {
        return this.detectionTime;
    }

    public Severity getSeverity() {
        return this.severity;
    }

    public String getDetectionMethod() {
        return this.detectionMethod;
    }

    public long getScanDurationMs() {
        return this.scanDurationMs;
    }

    public String getAdditionalInfo() {
        return this.additionalInfo;
    }

    public int getDuplicateCount() {
        return this.duplicatedItems.size();
    }

    public String getSummary() {
        return String.format("Duplication detected: UUID %s, %d copies, severity %s, %s",
                this.duplicatedUuid, this.getDuplicateCount(), this.severity.getDescription(),
                this.retainedItem.getItemDescription());
    }

    public String getDetailedDescription() {
        final StringBuilder desc = new StringBuilder();
        desc.append("Duplication detected:\n");
        desc.append("  UUID: ").append(this.duplicatedUuid).append("\n");
        desc.append("  Copies: ").append(this.getDuplicateCount()).append("\n");
        desc.append("  Severity: ").append(this.severity.getDescription()).append("\n");
        desc.append("  Method: ").append(this.detectionMethod).append("\n");
        desc.append("  Locations:\n");
        for (int i = 0; i < this.duplicatedItems.size(); i++) {
            final TrackedItem item = this.duplicatedItems.get(i);
            desc.append("    ").append(i + 1).append(". ")
                    .append(item.equals(this.retainedItem) ? "[kept] " : "[removed] ")
                    .append(item.getLocationDescription()).append(" - ")
                    .append(item.getItemDescription()).append("\n");
        }
        if (this.additionalInfo != null && !this.additionalInfo.isEmpty()) {
            desc.append("  Info: ").append(this.additionalInfo).append("\n");
        }
        return desc.toString();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        final DuplicationEvent that = (DuplicationEvent) obj;
        return Objects.equals(this.duplicatedUuid, that.duplicatedUuid)
                && Objects.equals(this.detectionTime, that.detectionTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.duplicatedUuid, this.detectionTime);
    }

    @Override
    public String toString() {
        return this.getSummary();
    }

    public static final class Builder {
        private String duplicatedUuid;
        private List<TrackedItem> duplicatedItems;
        private TrackedItem retainedItem;
        private Instant detectionTime = Instant.now();
        private Severity severity;
        private String detectionMethod = "automatic_scan";
        private long scanDurationMs = 0;
        private String additionalInfo;

        public Builder duplicatedUuid(final String uuid) {
            this.duplicatedUuid = uuid;
            return this;
        }

        public Builder duplicatedItems(final List<TrackedItem> items) {
            this.duplicatedItems = items;
            return this;
        }

        public Builder retainedItem(final TrackedItem retainedItem) {
            this.retainedItem = retainedItem;
            return this;
        }

        public Builder detectionTime(final Instant time) {
            this.detectionTime = time;
            return this;
        }

        public Builder severity(final Severity severity) {
            this.severity = severity;
            return this;
        }

        public Builder detectionMethod(final String method) {
            this.detectionMethod = method;
            return this;
        }

        public Builder scanDurationMs(final long duration) {
            this.scanDurationMs = duration;
            return this;
        }

        public Builder additionalInfo(final String info) {
            this.additionalInfo = info;
            return this;
        }

        public DuplicationEvent build() {
            Objects.requireNonNull(this.duplicatedUuid, "Duplicated UUID cannot be null");
            Objects.requireNonNull(this.duplicatedItems, "List of items cannot be null");
            if (this.duplicatedItems.size() < 2) {
                throw new IllegalArgumentException("At least 2 duplicated items required");
            }
            if (this.retainedItem == null) {
                this.retainedItem = this.duplicatedItems.get(0);
            } else if (!this.duplicatedItems.contains(this.retainedItem)) {
                throw new IllegalArgumentException("The retained item must be one of the duplicated items");
            }
            if (this.severity == null) {
                this.severity = severityFor(this.duplicatedItems.size());
            }
            return new DuplicationEvent(this);
        }

        private static Severity severityFor(final int copies) {
            if (copies >= 10) {
                return Severity.CRITICAL;
            }
            if (copies >= 5) {
                return Severity.HIGH;
            }
            return copies >= 3 ? Severity.MEDIUM : Severity.LOW;
        }
    }
}
