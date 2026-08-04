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

package fr.gaetandev.antiduplication.utils.nbt;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

/**
 * Stores the tag directly in the item NBT compound through CraftBukkit.
 *
 * <p>Used on servers without a persistent data container (1.8 to 1.13). The NMS
 * classes are resolved from the server package version and the compound method
 * names ({@code getTag}, {@code setTag}, {@code setString}...) are stable across
 * that whole range, so a single reflective implementation is enough.
 */
public final class LegacyNbtProvider implements NbtProvider {

    private final Method asNmsCopy;
    private final Method asBukkitCopy;
    private final Class<?> compoundClass;
    private final Method getTag;
    private final Method setTag;
    private final Method hasKey;
    private final Method getString;
    private final Method setString;
    private final Method removeKey;
    private final Method isEmpty;

    private LegacyNbtProvider() throws ReflectiveOperationException {
        final String version = serverPackageVersion();
        final Class<?> craftItemStackClass =
                Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
        final Class<?> nmsItemStackClass = Class.forName("net.minecraft.server." + version + ".ItemStack");
        this.compoundClass = Class.forName("net.minecraft.server." + version + ".NBTTagCompound");

        this.asNmsCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
        this.asBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);
        this.getTag = nmsItemStackClass.getMethod("getTag");
        this.setTag = nmsItemStackClass.getMethod("setTag", this.compoundClass);
        this.hasKey = this.compoundClass.getMethod("hasKey", String.class);
        this.getString = this.compoundClass.getMethod("getString", String.class);
        this.setString = this.compoundClass.getMethod("setString", String.class, String.class);
        this.removeKey = this.compoundClass.getMethod("remove", String.class);
        this.isEmpty = this.compoundClass.getMethod("isEmpty");
    }

    /**
     * @return a working provider, or {@code null} when the server layout is not
     *         the expected legacy CraftBukkit one
     */
    public static LegacyNbtProvider createOrNull() {
        try {
            final LegacyNbtProvider provider = new LegacyNbtProvider();
            return provider.selfTest() ? provider : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private boolean selfTest() {
        try {
            final ItemStack probe = new ItemStack(Material.STONE);
            return this.write(probe, "selftest", "ok") && "ok".equals(this.read(probe, "selftest"));
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static String serverPackageVersion() {
        final String[] parts = Bukkit.getServer().getClass().getPackage().getName().split("\\.");
        if (parts.length < 4) {
            throw new IllegalStateException("Unexpected server package: " + String.join(".", parts));
        }
        return parts[3];
    }

    @Override
    public String getName() {
        return "LegacyNBT";
    }

    @Override
    public String read(final ItemStack itemStack, final String key) {
        try {
            final Object compound = this.readCompound(itemStack);
            if (compound == null || !((Boolean) this.hasKey.invoke(compound, key))) {
                return null;
            }
            return (String) this.getString.invoke(compound, key);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean write(final ItemStack itemStack, final String key, final String value) {
        try {
            final Object nmsStack = this.asNmsCopy.invoke(null, itemStack);
            if (nmsStack == null) {
                return false;
            }
            Object compound = this.getTag.invoke(nmsStack);
            if (compound == null) {
                compound = this.compoundClass.getConstructor().newInstance();
            }
            this.setString.invoke(compound, key, value);
            this.setTag.invoke(nmsStack, compound);
            return this.applyTo(itemStack, nmsStack);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean remove(final ItemStack itemStack, final String key) {
        try {
            final Object nmsStack = this.asNmsCopy.invoke(null, itemStack);
            if (nmsStack == null) {
                return false;
            }
            final Object compound = this.getTag.invoke(nmsStack);
            if (compound == null || !((Boolean) this.hasKey.invoke(compound, key))) {
                return false;
            }
            this.removeKey.invoke(compound, key);
            this.setTag.invoke(nmsStack, ((Boolean) this.isEmpty.invoke(compound)) ? null : compound);
            return this.applyTo(itemStack, nmsStack);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String describe(final ItemStack itemStack) {
        try {
            final Object compound = this.readCompound(itemStack);
            return compound != null ? compound.toString() : "{}";
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "Error: " + e.getMessage();
        }
    }

    private Object readCompound(final ItemStack itemStack) throws ReflectiveOperationException {
        final Object nmsStack = this.asNmsCopy.invoke(null, itemStack);
        return nmsStack == null ? null : this.getTag.invoke(nmsStack);
    }

    /**
     * Copies the modified NBT back onto the caller's stack. Going through the
     * item meta is what makes the change stick on plain Bukkit stacks: unknown
     * NBT keys travel in the meta's unhandled tags.
     */
    private boolean applyTo(final ItemStack target, final Object nmsStack) throws ReflectiveOperationException {
        final ItemStack rebuilt = (ItemStack) this.asBukkitCopy.invoke(null, nmsStack);
        if (rebuilt == null) {
            return false;
        }
        final ItemMeta meta = rebuilt.getItemMeta();
        if (meta == null) {
            return false;
        }
        target.setItemMeta(meta);
        return true;
    }
}
