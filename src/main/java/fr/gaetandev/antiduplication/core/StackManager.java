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
import fr.gaetandev.antiduplication.model.TrackedItem;
import fr.gaetandev.antiduplication.utils.NBTUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StackManager {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final ItemManager itemManager;

    private final Map<Material, StackProperties> stackPropertiesCache;

    public StackManager(final AntiDuplicationPlugin plugin, final ConfigManager config, final ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.stackPropertiesCache = new ConcurrentHashMap<>();

        this.initializeStackProperties();
    }

    private void initializeStackProperties() {
        final Set<Material> nonStackable = EnumSet.of(
                Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.GOLD_SWORD, Material.STONE_SWORD, Material.WOOD_SWORD,
                Material.DIAMOND_PICKAXE, Material.IRON_PICKAXE, Material.GOLD_PICKAXE, Material.STONE_PICKAXE, Material.WOOD_PICKAXE,
                Material.DIAMOND_AXE, Material.IRON_AXE, Material.GOLD_AXE, Material.STONE_AXE, Material.WOOD_AXE,
                Material.DIAMOND_SPADE, Material.IRON_SPADE, Material.GOLD_SPADE, Material.STONE_SPADE, Material.WOOD_SPADE,
                Material.DIAMOND_HOE, Material.IRON_HOE, Material.GOLD_HOE, Material.STONE_HOE, Material.WOOD_HOE,
                Material.DIAMOND_HELMET, Material.IRON_HELMET, Material.GOLD_HELMET, Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET,
                Material.DIAMOND_CHESTPLATE, Material.IRON_CHESTPLATE, Material.GOLD_CHESTPLATE, Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE,
                Material.DIAMOND_LEGGINGS, Material.IRON_LEGGINGS, Material.GOLD_LEGGINGS, Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS,
                Material.DIAMOND_BOOTS, Material.IRON_BOOTS, Material.GOLD_BOOTS, Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS,
                Material.BOW, Material.FISHING_ROD, Material.FLINT_AND_STEEL, Material.SHEARS
        );

        final Map<Material, Integer> specialStackSizes = new HashMap<>();
        specialStackSizes.put(Material.ENDER_PEARL, 16);
        specialStackSizes.put(Material.SNOW_BALL, 16);
        specialStackSizes.put(Material.EGG, 16);
        specialStackSizes.put(Material.BUCKET, 1);
        specialStackSizes.put(Material.WATER_BUCKET, 1);
        specialStackSizes.put(Material.LAVA_BUCKET, 1);
        specialStackSizes.put(Material.MILK_BUCKET, 1);

        for (final Material material : Material.values()) {
            if (material == Material.AIR) {
                continue;
            }

            final StackProperties props = new StackProperties(
                    material,
                    !nonStackable.contains(material),
                    specialStackSizes.getOrDefault(material, material.getMaxStackSize()),
                    this.isValuableItem(material)
            );

            this.stackPropertiesCache.put(material, props);
        }

        this.plugin.getLogger().info("StackManager initialized with  " + this.stackPropertiesCache.size() + " materials");
    }

    public boolean processStack(final ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }

        final StackProperties props = this.getStackProperties(itemStack.getType());

        if (props.isStackable()) {
            return this.processStackableItem(itemStack, props);
        } else {
            return this.processNonStackableItem(itemStack, props);
        }
    }

    private boolean processStackableItem(final ItemStack itemStack, final StackProperties props) {

        if (this.hasUUIDAlready(itemStack)) {
            return this.validateStackSize(itemStack, props);
        }

        final boolean assigned = this.itemManager.assignBasicUUID(itemStack);

        if (assigned) {
            this.plugin.getLogger().finest(String.format(
                    "UUID assigned to stackable item: %s x%d",
                    itemStack.getType(), itemStack.getAmount()));
        }

        return assigned;
    }

    private boolean processNonStackableItem(final ItemStack itemStack, final StackProperties props) {
        if (this.hasUUIDAlready(itemStack)) {
            return false;
        }

        final boolean assigned = this.itemManager.assignBasicUUID(itemStack);

        if (assigned) {
            this.plugin.getLogger().finest(String.format(
                    "UUID assigned to non-stackable item: %s (durabilité: %d)",
                    itemStack.getType(), itemStack.getDurability()));
        }

        return assigned;
    }

    private boolean validateStackSize(final ItemStack itemStack, final StackProperties props) {
        final int currentAmount = itemStack.getAmount();
        final int maxStackSize = props.getMaxStackSize();

        if (currentAmount > maxStackSize) {
            this.plugin.getLogger().warning(String.format(
                    "Suspicious stack detected: %s x%d (max: %d)",
                    itemStack.getType(), currentAmount, maxStackSize));

            if (this.config.isStrictMode()) {
                itemStack.setAmount(maxStackSize);
                return true;
            }
        }

        return false;
    }

    public List<ItemStack> splitStack(final ItemStack originalStack, final int maxSize) {
        final List<ItemStack> result = new ArrayList<>();

        if (originalStack == null || originalStack.getAmount() <= maxSize) {
            result.add(originalStack);
            return result;
        }

        int remaining = originalStack.getAmount();

        while (remaining > 0) {
            final int stackSize = Math.min(remaining, maxSize);

            final ItemStack newStack = originalStack.clone();
            newStack.setAmount(stackSize);

            this.itemManager.assignBasicUUID(newStack);

            result.add(newStack);
            remaining -= stackSize;
        }

        this.plugin.getLogger().fine(String.format(
                "Split stack: %s x%d -> %d stacks",
                originalStack.getType(), originalStack.getAmount(), result.size()));

        return result;
    }

    public ItemStack mergeStacks(final List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return null;
        }

        if (stacks.size() == 1) {
            return stacks.get(0);
        }

        final ItemStack first = stacks.get(0);
        if (!this.areStacksCompatible(stacks)) {
            this.plugin.getLogger().warning("Attempting to merge incompatible stacks");
            return first;
        }

        final int totalAmount = stacks.stream().mapToInt(ItemStack::getAmount).sum();
        final StackProperties props = this.getStackProperties(first.getType());

        if (totalAmount > props.getMaxStackSize()) {
            this.plugin.getLogger().warning(String.format(
                    "Fusion impossible: total quantity (%d) > max stack (%d)",
                    totalAmount, props.getMaxStackSize()));
            return first;
        }

        final ItemStack merged = first.clone();
        merged.setAmount(totalAmount);

        this.itemManager.assignBasicUUID(merged);

        this.plugin.getLogger().fine(String.format(
                "Merged stacks: %d x %s -> 1 x %s x%d",
                stacks.size(), first.getType(), merged.getType(), totalAmount));

        return merged;
    }

    private boolean areStacksCompatible(final List<ItemStack> stacks) {
        if (stacks.size() < 2) {
            return true;
        }

        final ItemStack reference = stacks.get(0);

        for (int i = 1; i < stacks.size(); i++) {
            final ItemStack current = stacks.get(i);

            if (!this.areItemsCompatible(reference, current)) {
                return false;
            }
        }

        return true;
    }

    private boolean areItemsCompatible(final ItemStack item1, final ItemStack item2) {
        if (item1.getType() != item2.getType()) {
            return false;
        }
        if (item1.getDurability() != item2.getDurability()) {
            return false;
        }

        final ItemMeta meta1 = item1.getItemMeta();
        final ItemMeta meta2 = item2.getItemMeta();

        if (meta1 == null && meta2 == null) {
            return true;
        }
        if (meta1 == null || meta2 == null) {
            return false;
        }

        if (!Objects.equals(meta1.getDisplayName(), meta2.getDisplayName())) {
            return false;
        }

        final List<String> lore1 = meta1.hasLore() ? new ArrayList<>(meta1.getLore()) : new ArrayList<>();
        final List<String> lore2 = meta2.hasLore() ? new ArrayList<>(meta2.getLore()) : new ArrayList<>();

        lore1.removeIf(line -> line.contains(this.config.getNbtTag()));
        lore2.removeIf(line -> line.contains(this.config.getNbtTag()));

        return lore1.equals(lore2);
    }

    private boolean hasUUIDAlready(final ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return false;
        }

        final String uuid = NBTUtils.getNBTString(itemStack, this.config.getNbtTag());
        return uuid != null && !uuid.isEmpty();
    }

    private StackProperties getStackProperties(final Material material) {
        return this.stackPropertiesCache.getOrDefault(material,
                new StackProperties(material, true, 64, false));
    }

    private boolean isValuableItem(final Material material) {
        switch (material) {
            case DIAMOND:
            case DIAMOND_BLOCK:
            case EMERALD:
            case EMERALD_BLOCK:
            case GOLD_INGOT:
            case GOLD_BLOCK:
            case IRON_INGOT:
            case IRON_BLOCK:
            case BEACON:
            case NETHER_STAR:
                return true;
            default:
                final String name = material.name();
                return name.contains("DIAMOND_") ||
                        name.contains("EMERALD_") ||
                        name.contains("GOLD_") ||
                        name.equals("ENCHANTED_BOOK");
        }
    }

    public TrackedItem createTrackedItem(final ItemStack itemStack, final String container, final String playerName) {
        final String uuid = this.itemManager.extractUUID(itemStack);
        if (uuid == null) {
            return null;
        }

        final StackProperties props = this.getStackProperties(itemStack.getType());

        return new TrackedItem.Builder()
                .uuid(uuid)
                .fromItemStack(itemStack)
                .container(container)
                .playerName(playerName)
                .build();
    }

    public String generateStackStats() {
        final Map<Material, Integer> stackCounts = new HashMap<>();
        final Map<Material, Integer> valuableItems = new HashMap<>();

        for (final Map.Entry<Material, StackProperties> entry : this.stackPropertiesCache.entrySet()) {
            final Material material = entry.getKey();
            final StackProperties props = entry.getValue();

            stackCounts.put(material, props.getMaxStackSize());

            if (props.isValuable()) {
                valuableItems.put(material, props.getMaxStackSize());
            }
        }

        return String.format(
                "Stack Stats - Materials: %d, Precious: %d, Non-stackable: %d",
                this.stackPropertiesCache.size(),
                valuableItems.size(),
                (int) this.stackPropertiesCache.values().stream().filter(p -> !p.isStackable()).count()
        );
    }

    private static final class StackProperties {
        private final Material material;
        private final boolean stackable;
        private final int maxStackSize;
        private final boolean valuable;

        public StackProperties(final Material material, final boolean stackable, final int maxStackSize, final boolean valuable) {
            this.material = material;
            this.stackable = stackable;
            this.maxStackSize = maxStackSize;
            this.valuable = valuable;
        }

        public Material getMaterial() {
            return this.material;
        }

        public boolean isStackable() {
            return this.stackable;
        }

        public int getMaxStackSize() {
            return this.maxStackSize;
        }

        public boolean isValuable() {
            return this.valuable;
        }

        @Override
        public String toString() {
            return String.format("StackProperties{%s, stackable=%s, max=%d, valuable=%s}",
                    this.material, this.stackable, this.maxStackSize, this.valuable);
        }
    }
}