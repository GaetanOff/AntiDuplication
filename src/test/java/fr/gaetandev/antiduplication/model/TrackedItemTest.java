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

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackedItemTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static TrackedItem.Builder playerItem(final String uuid, final int slot) {
        return new TrackedItem.Builder()
                .uuid(uuid)
                .material(Material.DIAMOND_SWORD)
                .location(ItemLocation.ofPlayer(
                        ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "TestPlayer", slot));
    }

    @Test
    @DisplayName("The builder exposes what the logs need")
    void testBuilder() {
        final TrackedItem item = playerItem("TEST_12345678", 3).amount(1).build();

        assertThat(item.getUuid()).isEqualTo("TEST_12345678");
        assertThat(item.getMaterial()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(item.getPlayerName()).isEqualTo("TestPlayer");
        assertThat(item.getContainer()).isEqualTo("PLAYER_INVENTORY");
        assertThat(item.getLocation()).isNull();
        assertThat(item.getItemLocation().getSlot()).isEqualTo(3);
    }

    @Test
    @DisplayName("Container items report their block position, not a player")
    void testContainerItem() {
        final TrackedItem item = new TrackedItem.Builder()
                .uuid("TEST_87654321")
                .material(Material.DIAMOND_PICKAXE)
                .location(ItemLocation.ofContainer("CHEST", null, 7))
                .build();

        assertThat(item.getPlayerName()).isNull();
        assertThat(item.getContainer()).isEqualTo("CHEST");
        assertThat(item.getLocationDescription()).isEqualTo("CHEST at unknown position (slot 7)");
    }

    @Test
    @DisplayName("Item descriptions mention amount, durability and name only when relevant")
    void testItemDescription() {
        assertThat(playerItem("TEST_12345678", 0).build().getItemDescription())
                .isEqualTo("DIAMOND_SWORD");

        assertThat(playerItem("TEST_12345678", 0)
                .amount(3)
                .durability((short) 50)
                .displayName("Excalibur")
                .build()
                .getItemDescription())
                .isEqualTo("DIAMOND_SWORD x3 (durability: 50) \"Excalibur\"");
    }

    @Test
    @DisplayName("Copies at different locations are distinct, which is what a duplication is")
    void testEqualsIncludesLocation() {
        final TrackedItem inSlotThree = playerItem("TEST_12345678", 3).build();
        final TrackedItem sameSlot = playerItem("TEST_12345678", 3).amount(9).build();
        final TrackedItem otherSlot = playerItem("TEST_12345678", 8).build();
        final TrackedItem otherUuid = playerItem("TEST_87654321", 3).build();

        assertThat(inSlotThree).isEqualTo(sameSlot);
        assertThat(inSlotThree.hashCode()).isEqualTo(sameSlot.hashCode());
        assertThat(inSlotThree).isNotEqualTo(otherSlot);
        assertThat(inSlotThree).isNotEqualTo(otherUuid);
    }

    @Test
    @DisplayName("Re-dating a copy keeps its identity")
    void testWithFirstSeen() {
        final TrackedItem item = playerItem("TEST_12345678", 3).build();
        final Instant earlier = Instant.parse("2024-01-01T00:00:00Z");

        final TrackedItem redated = item.withFirstSeen(earlier);

        assertThat(redated.getFirstSeen()).isEqualTo(earlier);
        assertThat(redated.getLastSeen()).isEqualTo(item.getLastSeen());
        assertThat(redated).isEqualTo(item);
    }

    @Test
    @DisplayName("UUID, material and location are all required")
    void testBuilderRequiredFields() {
        assertThrows(NullPointerException.class, () -> new TrackedItem.Builder()
                .material(Material.STONE)
                .location(ItemLocation.ofContainer("CHEST", null, 0))
                .build());

        assertThrows(NullPointerException.class, () -> new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .location(ItemLocation.ofContainer("CHEST", null, 0))
                .build());

        assertThrows(NullPointerException.class, () -> new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.STONE)
                .build());
    }

    @Test
    @DisplayName("Timestamps default to now")
    void testTimestamps() {
        final Instant before = Instant.now();
        final TrackedItem item = playerItem("TEST_12345678", 0).build();
        final Instant after = Instant.now();

        assertThat(item.getFirstSeen()).isBetween(before, after);
        assertThat(item.getLastSeen()).isBetween(before, after);
    }
}
