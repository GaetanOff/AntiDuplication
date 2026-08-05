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

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value heuristic must stay name based: enum constants differ between game
 * versions, and referencing one that does not exist on the running server breaks
 * class loading.
 */
class StackManagerTest {

    @ParameterizedTest
    @DisplayName("Valuable materials are recognised")
    @EnumSource(value = Material.class, names = {
            "DIAMOND", "DIAMOND_BLOCK", "DIAMOND_SWORD", "EMERALD", "GOLD_INGOT",
            "IRON_INGOT", "BEACON", "NETHER_STAR", "DRAGON_EGG"})
    void testValuableMaterials(final Material material) {
        assertThat(StackManager.isValuableMaterial(material)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Common materials are not valuable")
    @EnumSource(value = Material.class, names = {"DIRT", "STONE", "COBBLESTONE", "SAND", "WOOD", "TORCH"})
    void testCommonMaterials(final Material material) {
        assertThat(StackManager.isValuableMaterial(material)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("Materials only present on newer versions are matched by name")
    @ValueSource(strings = {"NETHERITE_SWORD", "ELYTRA", "TOTEM_OF_UNDYING", "SHULKER_BOX",
            "BLUE_SHULKER_BOX", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"})
    void testModernMaterialNames(final String name) {
        // These constants do not exist in the API the plugin compiles against, so
        // the check runs the way the running server would: on the name alone.
        assertThat(StackManager.isValuableName(name)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Modern common materials stay out of the valuable set")
    @ValueSource(strings = {"OAK_PLANKS", "GRASS_BLOCK", "COPPER_INGOT", "AMETHYST_SHARD"})
    void testModernCommonNames(final String name) {
        assertThat(StackManager.isValuableName(name)).isFalse();
    }
}
