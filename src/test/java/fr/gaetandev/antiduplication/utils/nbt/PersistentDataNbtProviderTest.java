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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentDataNbtProviderTest {

    @ParameterizedTest
    @DisplayName("Configured tag names become valid namespaced keys")
    @CsvSource({
            "AntiDupeUUID, antidupeuuid",
            "'Anti Dupe:UUID#1', anti_dupe_uuid_1",
            "keep.me-1/ok, keep.me-1/ok",
            "'tag name', tag_name"
    })
    void testSanitize(final String raw, final String expected) {
        assertThat(PersistentDataNbtProvider.sanitize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("An empty tag name falls back to a usable key")
    void testSanitizeEmpty() {
        assertThat(PersistentDataNbtProvider.sanitize("")).isEqualTo("antidupe");
    }
}
