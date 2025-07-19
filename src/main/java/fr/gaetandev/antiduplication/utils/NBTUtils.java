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

import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagString;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

public final class NBTUtils {

    public static boolean setNBTString(final ItemStack itemStack, final String key, final String value) {
        if (itemStack == null || key == null || value == null) {
            return false;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);

            NBTTagCompound compound = nmsStack.getTag();
            if (compound == null) {
                compound = new NBTTagCompound();
            }

            compound.set(key, new NBTTagString(value));
            nmsStack.setTag(compound);

            final ItemStack modifiedStack = CraftItemStack.asBukkitCopy(nmsStack);
            copyItemStackData(modifiedStack, itemStack);

            return true;

        } catch (final Exception e) {
            return false;
        }
    }

    public static String getNBTString(final ItemStack itemStack, final String key) {
        if (itemStack == null || key == null) {
            return null;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);

            final NBTTagCompound compound = nmsStack.getTag();
            if (compound == null || !compound.hasKey(key)) {
                return null;
            }

            return compound.getString(key);

        } catch (final Exception e) {
            return null;
        }
    }

    public static boolean hasNBTKey(final ItemStack itemStack, final String key) {
        if (itemStack == null || key == null) {
            return false;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);

            final NBTTagCompound compound = nmsStack.getTag();
            return compound != null && compound.hasKey(key);

        } catch (final Exception e) {
            return false;
        }
    }

    public static boolean removeNBTKey(final ItemStack itemStack, final String key) {
        if (itemStack == null || key == null) {
            return false;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);

            final NBTTagCompound compound = nmsStack.getTag();
            if (compound == null || !compound.hasKey(key)) {
                return false;
            }

            compound.remove(key);
            nmsStack.setTag(compound.isEmpty() ? null : compound);

            final ItemStack modifiedStack = CraftItemStack.asBukkitCopy(nmsStack);
            copyItemStackData(modifiedStack, itemStack);

            return true;

        } catch (final Exception e) {
            return false;
        }
    }

    private static void copyItemStackData(final ItemStack source, final ItemStack target) {
        target.setType(source.getType());
        target.setAmount(source.getAmount());
        target.setDurability(source.getDurability());

        if (source.hasItemMeta()) {
            target.setItemMeta(source.getItemMeta());
        }
    }

    public static ItemStack cloneWithNBT(final ItemStack original) {
        if (original == null) {
            return null;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(original);
            return CraftItemStack.asBukkitCopy(nmsStack);
        } catch (final Exception e) {
            return original.clone();
        }
    }

    public static String getNBTDebugString(final ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }

        try {
            final net.minecraft.server.v1_8_R3.ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);
            final NBTTagCompound compound = nmsStack.getTag();

            return compound != null ? compound.toString() : "{}";

        } catch (final Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}