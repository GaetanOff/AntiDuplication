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

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Knows what may be stamped with a UUID and what a legal stack looks like.
 *
 * <p>Stack rules are read from {@link Material} at start-up instead of being
 * hard coded, so the same jar reports correct sizes on every game version.
 *
 * <p>A note on why stackable items are excluded by default: the UUID lives in
 * the item data, and Minecraft only merges stacks whose data is identical.
 * Stamping a unique UUID on a stackable item therefore stops it from stacking
 * at all, which is far more damaging than the duplication it is meant to catch.
 * Non-stackable items (tools, armour, enchanted books...) carry no such
 * constraint, and they are what duplication exploits actually target.
 */
public final class StackManager {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;

    /**
     * Per material properties, resolved once. An {@link EnumMap} is an array
     * lookup under the hood, which matters on the scan hot path.
     */
    private final Map<Material, StackProperties> stackProperties = new EnumMap<>(Material.class);

    private int nonStackableCount;
    private int valuableCount;

    public StackManager(final AntiDuplicationPlugin plugin, final ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.initializeStackProperties();
    }

    private void initializeStackProperties() {
        for (final Material material : Material.values()) {
            if (material == Material.AIR) {
                continue;
            }
            final int maxStackSize = safeMaxStackSize(material);
            final boolean valuable = isValuableMaterial(material);
            this.stackProperties.put(material, new StackProperties(material, maxStackSize, valuable));

            if (maxStackSize <= 1) {
                this.nonStackableCount++;
            }
            if (valuable) {
                this.valuableCount++;
            }
        }

        this.plugin.getLogger().info(String.format(
                "StackManager initialized: %d materials, %d non-stackable, %d valuable",
                this.stackProperties.size(), this.nonStackableCount, this.valuableCount));
    }

    /**
     * Some materials are known to throw when queried on legacy servers; a
     * conservative default keeps start-up going.
     */
    private static int safeMaxStackSize(final Material material) {
        try {
            final int size = material.getMaxStackSize();
            return size > 0 ? size : 1;
        } catch (final RuntimeException e) {
            return 64;
        }
    }

    /**
     * Whether this item takes part in UUID tracking at all.
     */
    public boolean isTrackable(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0) {
            return false;
        }
        return this.config.shouldTrackStackableItems() || this.getMaxStackSize(itemStack.getType()) <= 1;
    }

    public int getMaxStackSize(final Material material) {
        final StackProperties properties = this.stackProperties.get(material);
        return properties != null ? properties.getMaxStackSize() : safeMaxStackSize(material);
    }

    public boolean isValuable(final Material material) {
        final StackProperties properties = this.stackProperties.get(material);
        return properties != null ? properties.isValuable() : isValuableMaterial(material);
    }

    /**
     * Detects stacks larger than the game allows, a classic duplication symptom.
     *
     * @return the legal size when the stack is oversized, or {@code -1} when it is fine
     */
    public int getOversizedExcess(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return -1;
        }
        final int maxStackSize = this.getMaxStackSize(itemStack.getType());
        return itemStack.getAmount() > maxStackSize ? maxStackSize : -1;
    }

    static boolean isValuableMaterial(final Material material) {
        return isValuableName(material.name());
    }

    /**
     * Version-agnostic value heuristic: material names are matched instead of
     * enum constants, because constants such as {@code GOLD_SWORD} exist only on
     * legacy servers and would break class loading elsewhere.
     */
    static boolean isValuableName(final String name) {
        return name.startsWith("DIAMOND")
                || name.startsWith("EMERALD")
                || name.startsWith("NETHERITE")
                || name.startsWith("GOLD")
                || name.startsWith("GOLDEN")
                || name.startsWith("IRON")
                || name.contains("ENCHANTED")
                || name.equals("BEACON")
                || name.equals("NETHER_STAR")
                || name.equals("ELYTRA")
                || name.equals("TOTEM_OF_UNDYING")
                || name.equals("DRAGON_EGG")
                || name.equals("SHULKER_BOX")
                || name.endsWith("_SHULKER_BOX");
    }

    public String generateStackStats() {
        return String.format(
                "Stack Stats - Materials: %d, Valuable: %d, Non-stackable: %d, Tracking stackable items: %s",
                this.stackProperties.size(), this.valuableCount, this.nonStackableCount,
                this.config.shouldTrackStackableItems());
    }

    private static final class StackProperties {
        private final Material material;
        private final int maxStackSize;
        private final boolean valuable;

        StackProperties(final Material material, final int maxStackSize, final boolean valuable) {
            this.material = material;
            this.maxStackSize = maxStackSize;
            this.valuable = valuable;
        }

        int getMaxStackSize() {
            return this.maxStackSize;
        }

        boolean isValuable() {
            return this.valuable;
        }

        @Override
        public String toString() {
            return String.format("StackProperties{%s, max=%d, valuable=%s}",
                    this.material, this.maxStackSize, this.valuable);
        }
    }
}
