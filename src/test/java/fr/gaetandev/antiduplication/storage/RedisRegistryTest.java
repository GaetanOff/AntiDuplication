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

package fr.gaetandev.antiduplication.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRegistryTest {

    private static List<String> uuids(final int count) {
        final List<String> uuids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            uuids.add("AD_" + i);
        }
        return uuids;
    }

    @Test
    @DisplayName("Nothing to send produces no round trip")
    void testEmpty() {
        assertThat(RedisRegistry.partition(Collections.<String>emptyList())).isEmpty();
    }

    @Test
    @DisplayName("A batch smaller than the limit stays whole")
    void testSingleBatch() {
        final List<List<String>> batches = RedisRegistry.partition(uuids(10));

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(10).containsExactlyElementsOf(uuids(10));
    }

    @Test
    @DisplayName("Every value is sent exactly once, in bounded batches")
    void testManyBatches() {
        final List<String> all = uuids(1300);
        final List<List<String>> batches = RedisRegistry.partition(all);

        final List<String> flattened = new ArrayList<>();
        for (final List<String> batch : batches) {
            assertThat(batch).isNotEmpty();
            assertThat(batch.size()).isLessThanOrEqualTo(512);
            flattened.addAll(batch);
        }

        assertThat(flattened).containsExactlyElementsOf(all);
    }

    @Test
    @DisplayName("A count landing exactly on the limit does not leave an empty batch")
    void testExactMultiple() {
        final List<List<String>> batches = RedisRegistry.partition(uuids(1024));

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(512);
        assertThat(batches.get(1)).hasSize(512);
    }
}
