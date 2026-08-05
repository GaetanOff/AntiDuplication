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

package fr.gaetandev.antiduplication.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item data access needs a live server to round trip, so these tests cover the
 * contract that must hold without one: null safety and graceful degradation when
 * no strategy is available.
 */
class NBTUtilsTest {

    @AfterEach
    void tearDown() {
        NBTUtils.reset();
    }

    @Test
    @DisplayName("Null arguments never throw")
    void testNullSafety() {
        assertThat(NBTUtils.setNBTString(null, "key", "value")).isFalse();
        assertThat(NBTUtils.getNBTString(null, "key")).isNull();
        assertThat(NBTUtils.hasNBTKey(null, "key")).isFalse();
        assertThat(NBTUtils.removeNBTKey(null, "key")).isFalse();
        assertThat(NBTUtils.getNBTDebugString(null)).isNull();

        final ItemStack item = new ItemStack(Material.STONE);
        assertThat(NBTUtils.setNBTString(item, null, "value")).isFalse();
        assertThat(NBTUtils.getNBTString(item, null)).isNull();
        assertThat(NBTUtils.setNBTString(item, "key", null)).isFalse();
    }

    @Test
    @DisplayName("Accessors degrade gracefully with no strategy resolved")
    void testWithoutProvider() {
        final ItemStack item = new ItemStack(Material.DIAMOND_SWORD);

        assertThat(NBTUtils.isAvailable()).isFalse();
        assertThat(NBTUtils.getProviderName()).isEqualTo("unavailable");
        assertThat(NBTUtils.setNBTString(item, "key", "value")).isFalse();
        assertThat(NBTUtils.getNBTString(item, "key")).isNull();
        assertThat(NBTUtils.getNBTDebugString(item)).isEqualTo("{}");
    }

    @Test
    @DisplayName("Cloning keeps the stack intact and tolerates null")
    void testCloneWithNBT() {
        assertThat(NBTUtils.cloneWithNBT(null)).isNull();

        final ItemStack original = new ItemStack(Material.DIAMOND, 12);
        final ItemStack copy = NBTUtils.cloneWithNBT(original);

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getType()).isEqualTo(Material.DIAMOND);
        assertThat(copy.getAmount()).isEqualTo(12);
    }
}
