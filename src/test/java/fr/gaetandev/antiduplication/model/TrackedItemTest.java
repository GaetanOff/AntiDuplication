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
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class TrackedItemTest {

    @Test
    public void testTrackedItemBuilder() {
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

        assertEquals("UUID should match", uuid, item.getUuid());
        assertEquals("Container should match", "PLAYER_INVENTORY", item.getContainer());
        assertEquals("PlayerName should match", "TestPlayer", item.getPlayerName());
    }

    @Test
    public void testGetLocationDescription() {
        final TrackedItem playerItem = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.DIAMOND)
                .container("PLAYER_INVENTORY")
                .playerName("TestPlayer")
                .build();

        final String desc = playerItem.getLocationDescription();
        assertTrue("Description should contain player name", desc.contains("TestPlayer"));
        assertTrue("Description should contain container type", desc.contains("PLAYER_INVENTORY"));

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
        assertTrue("Description should contain coordinates", chestDesc.contains("100,64,200"));
        assertTrue("Description should contain world name", chestDesc.contains("world"));
    }

    @Test
    public void testGetItemDescription() {
        final TrackedItem item = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.DIAMOND_PICKAXE)
                .durability((short) 50)
                .container("TEST")
                .build();

        final String desc = item.getItemDescription();
        assertNotNull("Description should not be null", desc);
        assertTrue("Description should contain material name", desc.contains("DIAMOND_PICKAXE"));
        assertTrue("Description should contain durability", desc.contains("50"));
    }

    @Test
    public void testEqualsAndHashCode() {
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

        assertEquals("Items with the same UUID should be equal", item1a, item1b);
        assertEquals("HashCode should be the same for the same UUID", item1a.hashCode(), item1b.hashCode());
        assertNotEquals("Items with different UUIDs should not be equal", item1a, item2);
    }

    @Test(expected = NullPointerException.class)
    public void testBuilderRequiredFields() {
        new TrackedItem.Builder()
                .container("TEST")
                .build();
    }

    @Test
    public void testTimestamps() {
        final Instant before = Instant.now();

        final TrackedItem item = new TrackedItem.Builder()
                .uuid("TEST_12345678")
                .material(Material.GOLD_INGOT)
                .container("TEST")
                .build();

        final Instant after = Instant.now();

        assertNotNull("FirstSeen should not be null", item.getFirstSeen());
        assertNotNull("LastSeen should not be null", item.getLastSeen());
        assertTrue("FirstSeen should be after the start of the test", !item.getFirstSeen().isBefore(before));
        assertTrue("LastSeen should be before the end of the test", !item.getLastSeen().isAfter(after));
    }
}
