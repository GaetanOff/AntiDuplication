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

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of one item instance observed during a scan.
 *
 * <p>Two instances are the same tracked item only when they share a UUID
 * <em>and</em> a location: several instances with the same UUID at different
 * locations is exactly what a duplication is, so they must stay distinct in
 * collections.
 */
public final class TrackedItem {

    private final String uuid;
    private final Material material;
    private final int amount;
    private final short durability;
    private final String displayName;
    private final ItemLocation location;
    private final Instant firstSeen;
    private final Instant lastSeen;

    private TrackedItem(final Builder builder) {
        this.uuid = builder.uuid;
        this.material = builder.material;
        this.amount = builder.amount;
        this.durability = builder.durability;
        this.displayName = builder.displayName;
        this.location = builder.location;
        this.firstSeen = builder.firstSeen;
        this.lastSeen = builder.lastSeen;
    }

    public String getUuid() {
        return this.uuid;
    }

    public Material getMaterial() {
        return this.material;
    }

    public int getAmount() {
        return this.amount;
    }

    public short getDurability() {
        return this.durability;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public ItemLocation getItemLocation() {
        return this.location;
    }

    /**
     * @return the position of the container or dropped item, {@code null} for items held by a player
     */
    public Location getLocation() {
        return this.location.getBlockLocation();
    }

    /**
     * @return the kind of holder, as reported in the JSON logs
     */
    public String getContainer() {
        return this.location.getKind() == ItemLocation.Kind.CONTAINER
                ? this.location.getHolderName()
                : this.location.getKind().name();
    }

    /**
     * @return the owning player name, or {@code null} when the item is not player held
     */
    public String getPlayerName() {
        return this.location.getKind().isPlayerHeld() ? this.location.getHolderName() : null;
    }

    public Instant getFirstSeen() {
        return this.firstSeen;
    }

    public Instant getLastSeen() {
        return this.lastSeen;
    }

    public String getLocationDescription() {
        return this.location.describe();
    }

    public String getItemDescription() {
        final StringBuilder desc = new StringBuilder();
        desc.append(this.material.name());
        if (this.amount > 1) {
            desc.append(" x").append(this.amount);
        }
        if (this.durability > 0) {
            desc.append(" (durability: ").append(this.durability).append(")");
        }
        if (this.displayName != null && !this.displayName.isEmpty()) {
            desc.append(" \"").append(this.displayName).append("\"");
        }
        return desc.toString();
    }

    /**
     * Copy of this item with an earlier first-seen date, used when a UUID that
     * was already known is observed again.
     */
    public TrackedItem withFirstSeen(final Instant earlier) {
        return new Builder()
                .uuid(this.uuid)
                .material(this.material)
                .amount(this.amount)
                .durability(this.durability)
                .displayName(this.displayName)
                .location(this.location)
                .firstSeen(earlier)
                .lastSeen(this.lastSeen)
                .build();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        final TrackedItem that = (TrackedItem) obj;
        return Objects.equals(this.uuid, that.uuid) && Objects.equals(this.location, that.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.uuid, this.location);
    }

    @Override
    public String toString() {
        return String.format("TrackedItem{uuid='%s', item='%s', location='%s'}",
                this.uuid, this.getItemDescription(), this.getLocationDescription());
    }

    public static final class Builder {
        private String uuid;
        private Material material;
        private int amount = 1;
        private short durability = 0;
        private String displayName;
        private ItemLocation location;
        private Instant firstSeen = Instant.now();
        private Instant lastSeen = Instant.now();

        public Builder uuid(final String uuid) {
            this.uuid = uuid;
            return this;
        }

        /**
         * Reads material, amount, durability and display name off a live stack.
         * Must be called from the main thread.
         */
        public Builder fromItemStack(final ItemStack itemStack) {
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                this.material = itemStack.getType();
                this.amount = itemStack.getAmount();
                this.durability = itemStack.getDurability();
                if (itemStack.hasItemMeta()) {
                    // getItemMeta() copies the item data every call, so it is read
                    // once: this runs for every tracked item of every sweep.
                    final ItemMeta meta = itemStack.getItemMeta();
                    if (meta != null && meta.hasDisplayName()) {
                        this.displayName = meta.getDisplayName();
                    }
                }
            }
            return this;
        }

        public Builder material(final Material material) {
            this.material = material;
            return this;
        }

        public Builder amount(final int amount) {
            this.amount = amount;
            return this;
        }

        public Builder durability(final short durability) {
            this.durability = durability;
            return this;
        }

        public Builder displayName(final String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder location(final ItemLocation location) {
            this.location = location;
            return this;
        }

        public Builder firstSeen(final Instant firstSeen) {
            this.firstSeen = firstSeen;
            return this;
        }

        public Builder lastSeen(final Instant lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        public TrackedItem build() {
            Objects.requireNonNull(this.uuid, "UUID cannot be null");
            Objects.requireNonNull(this.material, "Material cannot be null");
            Objects.requireNonNull(this.location, "Location cannot be null");
            return new TrackedItem(this);
        }
    }
}
