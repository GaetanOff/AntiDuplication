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

import fr.gaetandev.antiduplication.utils.nbt.LegacyNbtProvider;
import fr.gaetandev.antiduplication.utils.nbt.NbtProvider;
import fr.gaetandev.antiduplication.utils.nbt.PersistentDataNbtProvider;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Entry point for custom item data.
 *
 * <p>{@link #initialize(Plugin)} picks the best strategy for the running server
 * once; every accessor is then a thin, null-safe delegation. All methods are
 * safe to call before initialization (they simply report failure), which keeps
 * unit tests free of a server instance.
 */
public final class NBTUtils {

    private static volatile NbtProvider provider;

    private NBTUtils() {
    }

    /**
     * Resolves the item data strategy for this server. Falls back from the
     * modern persistent data container to legacy NBT reflection.
     *
     * @return {@code true} when a working strategy was found
     */
    public static boolean initialize(final Plugin plugin) {
        NbtProvider resolved = PersistentDataNbtProvider.createOrNull(plugin);
        if (resolved == null) {
            resolved = LegacyNbtProvider.createOrNull();
        }
        provider = resolved;
        return resolved != null;
    }

    /**
     * Drops the resolved strategy; used on shutdown and by tests.
     */
    public static void reset() {
        provider = null;
    }

    public static String getProviderName() {
        final NbtProvider current = provider;
        return current != null ? current.getName() : "unavailable";
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    public static boolean setNBTString(final ItemStack itemStack, final String key, final String value) {
        final NbtProvider current = provider;
        if (current == null || !isUsable(itemStack) || key == null || value == null) {
            return false;
        }
        return current.write(itemStack, key, value);
    }

    public static String getNBTString(final ItemStack itemStack, final String key) {
        final NbtProvider current = provider;
        if (current == null || !isUsable(itemStack) || key == null) {
            return null;
        }
        return current.read(itemStack, key);
    }

    public static boolean hasNBTKey(final ItemStack itemStack, final String key) {
        return getNBTString(itemStack, key) != null;
    }

    public static boolean removeNBTKey(final ItemStack itemStack, final String key) {
        final NbtProvider current = provider;
        if (current == null || !isUsable(itemStack) || key == null) {
            return false;
        }
        return current.remove(itemStack, key);
    }

    /**
     * Deep copy preserving every custom tag. {@link ItemStack#clone()} already
     * carries the item meta over, unhandled NBT keys included.
     */
    public static ItemStack cloneWithNBT(final ItemStack original) {
        return original == null ? null : original.clone();
    }

    public static String getNBTDebugString(final ItemStack itemStack) {
        final NbtProvider current = provider;
        if (itemStack == null) {
            return null;
        }
        if (current == null || !isUsable(itemStack)) {
            return "{}";
        }
        return current.describe(itemStack);
    }

    /**
     * Air carries no item meta at all, so it can never hold custom data.
     */
    private static boolean isUsable(final ItemStack itemStack) {
        return itemStack != null && itemStack.getType() != Material.AIR;
    }
}
