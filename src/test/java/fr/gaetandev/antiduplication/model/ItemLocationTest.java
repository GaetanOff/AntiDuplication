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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemLocationTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_PLAYER_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Test
    @DisplayName("The owner key identifies the holder, not the slot")
    void testOwnerKeyIgnoresSlot() {
        final ItemLocation slotOne = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 1);
        final ItemLocation slotTwo = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 2);
        final ItemLocation otherPlayer = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, OTHER_PLAYER_ID, "Bob", 1);

        assertThat(slotOne.getOwnerKey()).isEqualTo(slotTwo.getOwnerKey());
        assertThat(slotOne.getOwnerKey()).isNotEqualTo(otherPlayer.getOwnerKey());
    }

    @Test
    @DisplayName("A player's inventory and their ender chest are different holders")
    void testOwnerKeySeparatesKinds() {
        final ItemLocation inventory = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 1);
        final ItemLocation enderChest = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_ENDER_CHEST, PLAYER_ID, "Alice", 1);

        assertThat(inventory.getOwnerKey()).isNotEqualTo(enderChest.getOwnerKey());
    }

    @Test
    @DisplayName("Locations compare by holder and slot")
    void testEqualsAndHashCode() {
        final ItemLocation first = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 4);
        final ItemLocation same = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 4);
        final ItemLocation differentSlot = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 5);

        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(first).isNotEqualTo(differentSlot);
        assertThat(first).isNotEqualTo(null);
        assertThat(first).isNotEqualTo("not a location");
    }

    @Test
    @DisplayName("Descriptions read well for every kind of holder")
    void testDescribe() {
        assertThat(ItemLocation.ofPlayer(ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 4).describe())
                .isEqualTo("Player Alice (Player inventory, slot 4)");
        assertThat(ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_CURSOR, PLAYER_ID, "Alice", ItemLocation.NO_SLOT).describe())
                .isEqualTo("Player Alice (Player cursor)");
        assertThat(ItemLocation.ofContainer("CHEST", null, 2).describe())
                .isEqualTo("CHEST at unknown position (slot 2)");
        assertThat(ItemLocation.ofGround(PLAYER_ID, null).describe())
                .isEqualTo("Dropped item at unknown position");
    }

    @Test
    @DisplayName("Only player held kinds report a player")
    void testIsPlayerHeld() {
        assertThat(ItemLocation.Kind.PLAYER_INVENTORY.isPlayerHeld()).isTrue();
        assertThat(ItemLocation.Kind.PLAYER_ENDER_CHEST.isPlayerHeld()).isTrue();
        assertThat(ItemLocation.Kind.PLAYER_CURSOR.isPlayerHeld()).isTrue();
        assertThat(ItemLocation.Kind.CONTAINER.isPlayerHeld()).isFalse();
        assertThat(ItemLocation.Kind.GROUND.isPlayerHeld()).isFalse();
        assertThat(ItemLocation.Kind.ENTITY_CONTAINER.isPlayerHeld()).isFalse();
    }

    @Test
    @DisplayName("Entity held kinds are the ones addressed by entity id")
    void testIsEntityHeld() {
        assertThat(ItemLocation.Kind.ENTITY_CONTAINER.isEntityHeld()).isTrue();
        assertThat(ItemLocation.Kind.ENTITY_EQUIPMENT.isEntityHeld()).isTrue();
        assertThat(ItemLocation.Kind.ENTITY_HELD.isEntityHeld()).isTrue();
        assertThat(ItemLocation.Kind.GROUND.isEntityHeld()).isTrue();
        assertThat(ItemLocation.Kind.PLAYER_INVENTORY.isEntityHeld()).isFalse();
        assertThat(ItemLocation.Kind.CONTAINER.isEntityHeld()).isFalse();
    }

    @Test
    @DisplayName("Items carried by one entity share an owner, whatever the slot or kind")
    void testEntityOwnerKey() {
        final ItemLocation cartSlotOne = ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_CONTAINER, PLAYER_ID, "MINECART_CHEST", null, 1);
        final ItemLocation cartSlotFive = ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_CONTAINER, PLAYER_ID, "MINECART_CHEST", null, 5);
        final ItemLocation wornByTheSameId = ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_EQUIPMENT, PLAYER_ID, "ARMOR_STAND", null, 1);

        assertThat(cartSlotOne.getOwnerKey()).isEqualTo(cartSlotFive.getOwnerKey());
        // A different kind of holding is a different holder, even on one entity.
        assertThat(cartSlotOne.getOwnerKey()).isNotEqualTo(wornByTheSameId.getOwnerKey());
    }

    @Test
    @DisplayName("Descriptions read well for entity holders")
    void testDescribeEntities() {
        assertThat(ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_EQUIPMENT, PLAYER_ID, "ARMOR_STAND", null, 2).describe())
                .isEqualTo("ARMOR_STAND (Entity equipment, slot 2) at unknown position");
        assertThat(ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_HELD, PLAYER_ID, "ITEM_FRAME", null, ItemLocation.NO_SLOT).describe())
                .isEqualTo("ITEM_FRAME (Displayed item) at unknown position");
    }

    @Test
    @DisplayName("The owner key is stable across calls")
    void testOwnerKeyIsStable() {
        final ItemLocation location = ItemLocation.ofPlayer(
                ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", 1);

        final String first = location.getOwnerKey();

        assertThat(location.getOwnerKey()).isEqualTo(first);
        assertThat(location.getOwnerKey()).isSameAs(first);
    }
}
