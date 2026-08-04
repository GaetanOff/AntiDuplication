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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UUIDGeneratorTest {

    private UUIDGenerator generator;

    @BeforeEach
    void setUp() {
        this.generator = new UUIDGenerator("TEST_", 8);
    }

    @Test
    @DisplayName("Generated identifiers carry the prefix and the configured length")
    void testGenerateUUID() {
        final String uuid = this.generator.generateUUID();

        assertThat(uuid).isNotNull().startsWith("TEST_").hasSize(13);
        assertThat(this.generator.isValidUUID(uuid)).isTrue();
    }

    @Test
    @DisplayName("Requested lengths are clamped to the supported range")
    void testLengthClamping() {
        assertThat(new UUIDGenerator("", 1).generateUUID()).hasSize(UUIDGenerator.MIN_LENGTH);
        assertThat(new UUIDGenerator("", 999).generateUUID()).hasSize(UUIDGenerator.MAX_LENGTH);
    }

    @Test
    @DisplayName("A large run never repeats an identifier")
    void testUUIDUniqueness() {
        final int count = 10_000;
        final Set<String> uuids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            uuids.add(this.generator.generateUUID());
        }
        assertThat(uuids).hasSize(count);
    }

    @Test
    @DisplayName("Concurrent generation stays unique")
    void testConcurrentUniqueness() throws InterruptedException {
        final int threads = 8;
        final int perThread = 500;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final Set<String> uuids = Collections.synchronizedSet(new HashSet<>());
        final CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            uuids.add(this.generator.generateUUID());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(uuids).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("Validation rejects foreign and malformed identifiers")
    void testIsValidUUID() {
        assertThat(this.generator.isValidUUID(this.generator.generateUUID())).isTrue();
        assertThat(this.generator.isValidUUID(null)).isFalse();
        assertThat(this.generator.isValidUUID("")).isFalse();
        assertThat(this.generator.isValidUUID("ABCD1234")).isFalse();
        assertThat(this.generator.isValidUUID("WRONG_ABCD1234")).isFalse();
        assertThat(this.generator.isValidUUID("TEST_SHORT")).isFalse();
        assertThat(this.generator.isValidUUID("TEST_ABCDEFG!")).isFalse();
        // I, O, 0 and 1 are excluded from the alphabet to stay unambiguous.
        assertThat(this.generator.isValidUUID("TEST_ABCDEFGI")).isFalse();
    }

    @Test
    @DisplayName("The identifier body can be read back without the prefix")
    void testExtractUUIDPart() {
        final String fullUuid = this.generator.generateUUID();
        final String uuidPart = this.generator.extractUUIDPart(fullUuid);

        assertThat(uuidPart).isNotNull().hasSize(8).doesNotContain("TEST_");
        assertThat(this.generator.extractUUIDPart("WRONG_ABCDEFGH")).isNull();
    }

    @Test
    @DisplayName("Batches are unique and valid")
    void testGenerateBatch() {
        final List<String> batch = Arrays.asList(this.generator.generateBatch(100));

        assertThat(batch).hasSize(100).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(batch).allMatch(this.generator::isValidUUID);
    }

    @Test
    @DisplayName("Degenerate batch sizes yield an empty array")
    void testDegenerateBatch() {
        assertThat(this.generator.generateBatch(0)).isEmpty();
        assertThat(this.generator.generateBatch(-5)).isEmpty();
    }

    @Test
    @DisplayName("Statistics reflect the generated volume")
    void testGeneratorStats() {
        for (int i = 0; i < 10; i++) {
            this.generator.generateUUID();
        }
        final UUIDGenerator.GeneratorStats stats = this.generator.getStats();

        assertThat(stats).isNotNull();
        assertThat(stats.getCacheSize()).isEqualTo(10);
        assertThat(stats.getGenerated()).isEqualTo(10);
        assertThat(stats.getCollisions()).isZero();
        assertThat(stats.getPrefix()).isEqualTo("TEST_");
        assertThat(stats.getLength()).isEqualTo(8);
        assertThat(stats.getCollisionProbability()).isGreaterThanOrEqualTo(0.0);
        assertThat(stats.toString()).contains("TEST_");
    }
}
