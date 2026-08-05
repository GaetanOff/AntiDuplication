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

package fr.gaetandev.antiduplication.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdminActionListenerTest {

    @ParameterizedTest
    @DisplayName("The command root is extracted whatever the form")
    @CsvSource({
            "/give Alice diamond 64, give",
            "give Alice diamond 64, give",
            "/minecraft:give Alice diamond, give",
            "/GIVE Alice diamond, give",
            "'  /give   Alice', give",
            "/gamemode creative, gamemode",
            "/i diamond, i"
    })
    void testCommandRoot(final String rawCommand, final String expected) {
        assertThat(AdminActionListener.commandRoot(rawCommand)).isEqualTo(expected);
    }

    @Test
    @DisplayName("A command with no name yields nothing to match")
    void testEmptyCommand() {
        assertThat(AdminActionListener.commandRoot("/")).isNull();
        assertThat(AdminActionListener.commandRoot("   ")).isNull();
    }
}
