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

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the tag in the item's {@code PersistentDataContainer} (Bukkit 1.14+).
 *
 * <p>The API is reached through reflection because the plugin compiles against
 * the 1.8 API. This is the preferred strategy whenever it is available: it is
 * pure Bukkit, so it keeps working across obfuscation changes (1.17+).
 */
public final class PersistentDataNbtProvider implements NbtProvider {

    private final Plugin plugin;
    private final Constructor<?> namespacedKeyConstructor;
    private final Method getContainer;
    private final Method containerSet;
    private final Method containerGet;
    private final Method containerRemove;
    private final Method containerKeys;
    private final Object stringType;

    /** Namespaced keys are immutable and costly to build: cache them per tag. */
    private final Map<String, Object> keyCache = new ConcurrentHashMap<>();

    private PersistentDataNbtProvider(final Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;

        final Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
        final Class<?> holderClass = Class.forName("org.bukkit.persistence.PersistentDataHolder");
        final Class<?> containerClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
        final Class<?> typeClass = Class.forName("org.bukkit.persistence.PersistentDataType");

        this.namespacedKeyConstructor = namespacedKeyClass.getConstructor(Plugin.class, String.class);
        this.getContainer = holderClass.getMethod("getPersistentDataContainer");
        this.containerSet = containerClass.getMethod("set", namespacedKeyClass, typeClass, Object.class);
        this.containerGet = containerClass.getMethod("get", namespacedKeyClass, typeClass);
        this.containerRemove = containerClass.getMethod("remove", namespacedKeyClass);
        this.containerKeys = containerClass.getMethod("getKeys");
        this.stringType = typeClass.getField("STRING").get(null);
    }

    /**
     * @return a working provider, or {@code null} when the running server has no
     *         persistent data container support
     */
    public static PersistentDataNbtProvider createOrNull(final Plugin plugin) {
        try {
            final PersistentDataNbtProvider provider = new PersistentDataNbtProvider(plugin);
            return provider.selfTest() ? provider : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * A successful round trip on a throw-away item proves the reflection chain
     * is wired correctly before the plugin commits to this strategy.
     */
    private boolean selfTest() {
        try {
            final ItemStack probe = new ItemStack(org.bukkit.Material.STONE);
            return this.write(probe, "selftest", "ok") && "ok".equals(this.read(probe, "selftest"));
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "PersistentDataContainer";
    }

    @Override
    public String read(final ItemStack itemStack, final String key) {
        try {
            final ItemMeta meta = itemStack.getItemMeta();
            if (meta == null) {
                return null;
            }
            final Object container = this.getContainer.invoke(meta);
            final Object value = this.containerGet.invoke(container, this.namespacedKey(key), this.stringType);
            return value instanceof String ? (String) value : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean write(final ItemStack itemStack, final String key, final String value) {
        try {
            final ItemMeta meta = itemStack.getItemMeta();
            if (meta == null) {
                return false;
            }
            final Object container = this.getContainer.invoke(meta);
            this.containerSet.invoke(container, this.namespacedKey(key), this.stringType, value);
            itemStack.setItemMeta(meta);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean remove(final ItemStack itemStack, final String key) {
        try {
            final ItemMeta meta = itemStack.getItemMeta();
            if (meta == null) {
                return false;
            }
            final Object container = this.getContainer.invoke(meta);
            this.containerRemove.invoke(container, this.namespacedKey(key));
            itemStack.setItemMeta(meta);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String describe(final ItemStack itemStack) {
        try {
            final ItemMeta meta = itemStack.getItemMeta();
            if (meta == null) {
                return "{}";
            }
            final Object container = this.getContainer.invoke(meta);
            final Object keys = this.containerKeys.invoke(container);
            return keys instanceof Collection ? keys.toString() : "{}";
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "Error: " + e.getMessage();
        }
    }

    private Object namespacedKey(final String key) {
        return this.keyCache.computeIfAbsent(key, raw -> {
            try {
                return this.namespacedKeyConstructor.newInstance(this.plugin, sanitize(raw));
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to build a namespaced key for " + raw, e);
            }
        });
    }

    /**
     * Namespaced keys only accept {@code [a-z0-9/._-]}, while the configured tag
     * name is free form.
     */
    static String sanitize(final String key) {
        final StringBuilder sanitized = new StringBuilder(key.length());
        for (final char c : key.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            sanitized.append(isAllowed(c) ? c : '_');
        }
        return sanitized.length() == 0 ? "antidupe" : sanitized.toString();
    }

    private static boolean isAllowed(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-' || c == '/';
    }
}
