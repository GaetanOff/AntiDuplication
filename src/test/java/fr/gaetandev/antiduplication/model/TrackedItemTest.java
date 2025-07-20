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
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TrackedItemTest {

    @Test
    @DisplayName("Test TrackedItem builder")
    void testTrackedItemBuilder() {
        final String uuid = "TEST_12345678";
        final Material material = Material.DIAMOND;
        final int amount = 5;

        final TrackedItem item = new TrackedItem.Builder()
                .uuid(uuid)
                .fromItemStack(null)
                .container("PLAYER_INVENTORY")
                .playerName("TestPlayer")
                .material(Material.STONE)
                .build();

        assertThat(item.getUuid()).isEqualTo(uuid);
        assertThat(item.getContainer()).isEqualTo("PLAYER_INVENTORY");
        assertThat(item.getPlayerName()).isEqualTo("TestPlayer");
    }

    @Test
    @DisplayName("Test location description generation")
    void testGetLocationDescription() {
        final TrackedItem playerItem = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.DIAMOND)
                .container("PLAYER_INVENTORY")
                .playerName("TestPlayer")
                .build();

        final String desc = playerItem.getLocationDescription();
        assertThat(desc).isEqualTo("Player TestPlayer (PLAYER_INVENTORY)");

        final World mockWorld = Mockito.mock(World.class);
        when(mockWorld.getName()).thenReturn("world");
        final Location location = new Location(mockWorld, 100, 64, 200);

        final TrackedItem chestItem = new TrackedItem.Builder()
                .uuid("TEST_87654321")
                .material(Material.CHEST)
                .container("CHEST")
                .location(location)
                .build();

        final String chestDesc = chestItem.getLocationDescription();
        assertThat(chestDesc).isEqualTo("CHEST at 100,64,200 in world");
    }

    @Test
    @DisplayName("Test item description generation")
    void testGetItemDescription() {
        final TrackedItem item = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.DIAMOND_PICKAXE)
                .durability((short) 50)
                .container("TEST")
                .build();

        final String desc = item.getItemDescription();
        assertThat(desc).isEqualTo("DIAMOND_PICKAXE (durability: 50)");
    }

    @Test
    @DisplayName("Test equals and hashCode methods")
    void testEqualsAndHashCode() {
        final String uuid1 = "TEST_12345678";
        final String uuid2 = "TEST_87654321";

        final TrackedItem item1a = new TrackedItem.Builder()
                .uuid(uuid1)
                .material(Material.STONE)
                .container("TEST")
                .build();

        final TrackedItem item1b = new TrackedItem.Builder()
                .uuid(uuid1)
                .material(Material.STONE)
                .container("DIFFERENT")
                .playerName("DifferentPlayer")
                .build();

        final TrackedItem item2 = new TrackedItem.Builder()
                .uuid(uuid2)
                .material(Material.DIRT)
                .container("TEST")
                .build();

        assertThat(item1a).isEqualTo(item1b);
        assertThat(item1a.hashCode()).isEqualTo(item1b.hashCode());
        assertThat(item1a).isNotEqualTo(item2);
    }

    @Test
    @DisplayName("Test required fields in builder")
    void testBuilderRequiredFields() {
        assertThrows(NullPointerException.class, () ->
                new TrackedItem.Builder()
                        .container("TEST")
                        .build()
        );
    }

    @Test
    @DisplayName("Test timestamp management")
    void testTimestamps() {
        final Instant before = Instant.now();

        final TrackedItem item = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.GOLD_INGOT)
                .container("TEST")
                .build();

        final Instant after = Instant.now();

        assertThat(item.getFirstSeen()).isNotNull();
        assertThat(item.getLastSeen()).isNotNull();
        assertThat(item.getFirstSeen()).isAfterOrEqualTo(before);
        assertThat(item.getLastSeen()).isBeforeOrEqualTo(after);
    }
}
