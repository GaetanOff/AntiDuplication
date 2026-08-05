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

import java.util.Objects;
import java.util.UUID;

/**
 * Where a tracked item was observed, expressed precisely enough to find it
 * again on the main thread and delete it.
 *
 * <p>Instances are immutable and hold no live item reference, which makes them
 * safe to hand over to the analysis threads.
 */
public final class ItemLocation {

    /**
     * Kinds of holder, each with the rank used to decide which copy of a
     * duplicated item survives: the lower the rank, the stronger the claim.
     * Ranking is explicit rather than relying on declaration order, so adding a
     * kind cannot silently reshuffle the decision.
     */
    public enum Kind {
        PLAYER_INVENTORY("Player inventory", 0),
        PLAYER_ENDER_CHEST("Ender chest", 1),
        PLAYER_CURSOR("Player cursor", 2),
        CONTAINER("Container", 3),
        ENTITY_CONTAINER("Entity container", 4),
        ENTITY_EQUIPMENT("Entity equipment", 5),
        ENTITY_HELD("Displayed item", 6),
        GROUND("Dropped item", 7);

        private final String description;
        private final int retentionRank;

        Kind(final String description, final int retentionRank) {
            this.description = description;
            this.retentionRank = retentionRank;
        }

        public String getDescription() {
            return this.description;
        }

        /**
         * @return how strong this holder's claim to the item is when a tie has to
         *         be broken; lower wins
         */
        public int getRetentionRank() {
            return this.retentionRank;
        }

        public boolean isPlayerHeld() {
            return this == PLAYER_INVENTORY || this == PLAYER_ENDER_CHEST || this == PLAYER_CURSOR;
        }

        /**
         * @return whether the holder is an entity addressed by its unique id
         */
        public boolean isEntityHeld() {
            return this == ENTITY_CONTAINER || this == ENTITY_EQUIPMENT
                    || this == ENTITY_HELD || this == GROUND;
        }
    }

    /** Slot value for locations that are not backed by an indexed slot. */
    public static final int NO_SLOT = -1;

    private final Kind kind;
    private final UUID holderId;
    private final String holderName;
    private final Location blockLocation;
    private final int slot;

    /**
     * Derived from the fields above and never changes. Computed on first use
     * because it is asked for once per tracked item on every scan.
     */
    private String ownerKey;

    private ItemLocation(final Kind kind, final UUID holderId, final String holderName,
                         final Location blockLocation, final int slot) {
        this.kind = Objects.requireNonNull(kind, "Kind cannot be null");
        this.holderId = holderId;
        this.holderName = holderName;
        this.blockLocation = blockLocation;
        this.slot = slot;
    }

    public static ItemLocation ofPlayer(final Kind kind, final UUID playerId, final String playerName, final int slot) {
        return new ItemLocation(kind, playerId, playerName, null, slot);
    }

    public static ItemLocation ofContainer(final String containerName, final Location blockLocation, final int slot) {
        return new ItemLocation(Kind.CONTAINER, null, containerName, blockLocation, slot);
    }

    /**
     * An item held by an entity: inside its inventory, worn as equipment, or
     * displayed by it.
     */
    public static ItemLocation ofEntity(final Kind kind, final UUID entityId, final String entityName,
                                        final Location location, final int slot) {
        return new ItemLocation(kind, entityId, entityName, location, slot);
    }

    public static ItemLocation ofGround(final UUID entityId, final Location location) {
        return new ItemLocation(Kind.GROUND, entityId, "GROUND", location, NO_SLOT);
    }

    public Kind getKind() {
        return this.kind;
    }

    /**
     * @return the player or entity unique id, {@code null} for block containers
     */
    public UUID getHolderId() {
        return this.holderId;
    }

    /**
     * @return the player name, entity type or container type, never {@code null}
     *         in practice
     */
    public String getHolderName() {
        return this.holderName;
    }

    public Location getBlockLocation() {
        return this.blockLocation;
    }

    public int getSlot() {
        return this.slot;
    }

    /**
     * Stable identity of the holder, used to recognise the original owner of a
     * UUID from one scan to the next. Deliberately excludes the slot: moving an
     * item within one inventory does not change who owns it.
     */
    public String getOwnerKey() {
        String key = this.ownerKey;
        if (key == null) {
            key = this.computeOwnerKey();
            this.ownerKey = key;
        }
        return key;
    }

    private String computeOwnerKey() {
        if (this.holderId != null) {
            return this.kind.name() + ':' + this.holderId;
        }
        if (this.blockLocation != null) {
            return this.kind.name() + ':' + describeBlock(this.blockLocation);
        }
        return this.kind.name() + ':' + this.holderName;
    }

    public String describe() {
        switch (this.kind) {
            case PLAYER_INVENTORY:
            case PLAYER_ENDER_CHEST:
            case PLAYER_CURSOR:
                return this.slot == NO_SLOT
                        ? String.format("Player %s (%s)", this.holderName, this.kind.getDescription())
                        : String.format("Player %s (%s, slot %d)",
                        this.holderName, this.kind.getDescription(), this.slot);
            case CONTAINER:
                return String.format("%s at %s (slot %d)",
                        this.holderName, describeBlock(this.blockLocation), this.slot);
            case ENTITY_CONTAINER:
            case ENTITY_EQUIPMENT:
                return String.format("%s (%s, slot %d) at %s", this.holderName,
                        this.kind.getDescription(), this.slot, describeBlock(this.blockLocation));
            case ENTITY_HELD:
                return String.format("%s (%s) at %s", this.holderName,
                        this.kind.getDescription(), describeBlock(this.blockLocation));
            case GROUND:
                return String.format("Dropped item at %s", describeBlock(this.blockLocation));
            default:
                return this.kind.getDescription();
        }
    }

    /**
     * World name of this location, or {@code null} when there is none to report.
     *
     * <p>{@link Location#getWorld()} throws once the world has been unloaded on
     * modern servers, which would otherwise propagate out of {@code equals} or a
     * log call — places that must never fail.
     */
    public String getWorldName() {
        return this.blockLocation == null ? null : worldNameOf(this.blockLocation);
    }

    private static String describeBlock(final Location location) {
        if (location == null) {
            return "unknown position";
        }
        final String world = worldNameOf(location);
        return String.format("%d,%d,%d in %s", location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), world != null ? world : "unknown world");
    }

    private static String worldNameOf(final Location location) {
        try {
            final org.bukkit.World world = location.getWorld();
            return world != null ? world.getName() : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        final ItemLocation that = (ItemLocation) obj;
        return this.slot == that.slot
                && this.kind == that.kind
                && Objects.equals(this.holderId, that.holderId)
                && Objects.equals(this.holderName, that.holderName)
                && Objects.equals(blockKey(this.blockLocation), blockKey(that.blockLocation));
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.kind, this.holderId, this.holderName, blockKey(this.blockLocation), this.slot);
    }

    /**
     * {@link Location} compares world identity and exact coordinates; only the
     * block a container sits on matters here.
     */
    private static String blockKey(final Location location) {
        return location == null ? null : describeBlock(location);
    }

    @Override
    public String toString() {
        return this.describe();
    }
}
