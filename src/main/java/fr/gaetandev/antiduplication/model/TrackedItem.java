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

import java.time.Instant;
import java.util.Objects;

public final class TrackedItem {

    private final String uuid;
    private final Material material;
    private final int amount;
    private final short durability;
    private final String displayName;
    private final Location location;
    private final String container;
    private final String playerName;
    private final Instant firstSeen;
    private final Instant lastSeen;

    private TrackedItem(final Builder builder) {
        this.uuid = builder.uuid;
        this.material = builder.material;
        this.amount = builder.amount;
        this.durability = builder.durability;
        this.displayName = builder.displayName;
        this.location = builder.location;
        this.container = builder.container;
        this.playerName = builder.playerName;
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

    public Location getLocation() {
        return this.location;
    }

    public String getContainer() {
        return this.container;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public Instant getFirstSeen() {
        return this.firstSeen;
    }

    public Instant getLastSeen() {
        return this.lastSeen;
    }

    public String getLocationDescription() {
        if (this.playerName != null) {
            return String.format("Player %s (%s)", this.playerName, this.container);
        } else if (this.location != null) {
            return String.format("%s at %d,%d,%d in %s",
                    this.container,
                    this.location.getBlockX(),
                    this.location.getBlockY(),
                    this.location.getBlockZ(),
                    this.location.getWorld().getName());
        } else {
            return this.container + " (unknown position)";
        }
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

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        final TrackedItem that = (TrackedItem) obj;
        return Objects.equals(this.uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.uuid);
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
        private Location location;
        private String container;
        private String playerName;
        private Instant firstSeen = Instant.now();
        private Instant lastSeen = Instant.now();

        public Builder uuid(final String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder fromItemStack(final ItemStack itemStack) {
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                this.material = itemStack.getType();
                this.amount = itemStack.getAmount();
                this.durability = itemStack.getDurability();
                if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName()) {
                    this.displayName = itemStack.getItemMeta().getDisplayName();
                }
            }
            return this;
        }

        public Builder material(final Material material) {
            this.material = material;
            return this;
        }

        public Builder durability(final short durability) {
            this.durability = durability;
            return this;
        }

        public Builder location(final Location location) {
            this.location = location;
            return this;
        }

        public Builder container(final String container) {
            this.container = container;
            return this;
        }

        public Builder playerName(final String playerName) {
            this.playerName = playerName;
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
            Objects.requireNonNull(this.container, "Container cannot be null");
            return new TrackedItem(this);
        }
    }
}
