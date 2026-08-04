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

/**
 * Reads and writes a single string tag on an item, hiding the server
 * implementation behind a stable contract.
 *
 * <p>Implementations are resolved at runtime so that one jar supports every
 * server version; all methods are expected to fail softly (returning
 * {@code null}/{@code false}) rather than throwing.
 */
public interface NbtProvider {

    /**
     * Short identifier of the backing strategy, used in start-up logs.
     */
    String getName();

    /**
     * @return the stored value, or {@code null} when the item carries no such tag
     */
    String read(ItemStack itemStack, String key);

    /**
     * Stores {@code value} on {@code itemStack}, mutating it in place.
     */
    boolean write(ItemStack itemStack, String key, String value);

    /**
     * Removes {@code key} from {@code itemStack}, mutating it in place.
     */
    boolean remove(ItemStack itemStack, String key);

    /**
     * Human readable dump of the custom data carried by the item, for debugging.
     */
    String describe(ItemStack itemStack);
}
