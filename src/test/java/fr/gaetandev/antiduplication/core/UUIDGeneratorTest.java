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

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class UUIDGeneratorTest {

    private UUIDGenerator generator;

    @Before
    public void setUp() {
        this.generator = new UUIDGenerator("TEST_", 8);
    }

    @Test
    public void testGenerateUUID() {
        final String uuid = this.generator.generateUUID();
        assertNotNull("UUID should not be null", uuid);
        assertTrue("UUID should start with the prefix", uuid.startsWith("TEST_"));
        assertEquals("UUID should have the correct length", 13, uuid.length()); // 5 (prefix) + 8
    }

    @Test
    public void testUUIDUniqueness() {
        final Set<String> uuids = new HashSet<>();
        final int count = 10000;
        for (int i = 0; i < count; i++) {
            final String uuid = this.generator.generateUUID();
            assertFalse("Duplicate UUID generated: " + uuid, uuids.contains(uuid));
            uuids.add(uuid);
        }
        assertEquals("Incorrect number of unique UUIDs", count, uuids.size());
    }

    @Test
    public void testIsValidUUID() {
        final String validUuid = this.generator.generateUUID();
        assertTrue("Generated UUID should be valid", this.generator.isValidUUID(validUuid));
        assertFalse("Null UUID should be invalid", this.generator.isValidUUID(null));
        assertFalse("Empty string should be invalid", this.generator.isValidUUID(""));
        assertFalse("UUID without prefix should be invalid", this.generator.isValidUUID("ABCD1234"));
        assertFalse("UUID with wrong prefix should be invalid", this.generator.isValidUUID("WRONG_ABCD1234"));
    }

    @Test
    public void testExtractUUIDPart() {
        final String fullUuid = this.generator.generateUUID();
        final String uuidPart = this.generator.extractUUIDPart(fullUuid);
        assertNotNull("UUID part should not be null", uuidPart);
        assertEquals("UUID part should have the correct length", 8, uuidPart.length());
        assertFalse("UUID part should not contain the prefix", uuidPart.contains("TEST_"));
    }

    @Test
    public void testGenerateBatch() {
        final int batchSize = 100;
        final String[] batch = this.generator.generateBatch(batchSize);
        assertNotNull("Batch should not be null", batch);
        assertEquals("Batch should have the correct size", batchSize, batch.length);
        final Set<String> uniqueUuids = new HashSet<>();
        for (final String uuid : batch) {
            assertNotNull("UUID in batch should not be null", uuid);
            assertTrue("UUID in batch should be valid", this.generator.isValidUUID(uuid));
            uniqueUuids.add(uuid);
        }
        assertEquals("All UUIDs in batch should be unique", batchSize, uniqueUuids.size());
    }

    @Test
    public void testGeneratorStats() {
        for (int i = 0; i < 10; i++) {
            this.generator.generateUUID();
        }
        final UUIDGenerator.GeneratorStats stats = this.generator.getStats();
        assertNotNull("Stats should not be null", stats);
        assertTrue("Cache size should be > 0", stats.getCacheSize() > 0);
        assertEquals("Prefix should match", "TEST_", stats.getPrefix());
        assertEquals("Length should match", 8, stats.getLength());
        assertTrue("Collision probability should be >= 0", stats.getCollisionProbability() >= 0.0);
    }

    @Test
    public void testEmptyBatch() {
        final String[] batch = this.generator.generateBatch(0);
        assertNotNull("Empty batch should not be null", batch);
        assertEquals("Empty batch should have length 0", 0, batch.length);
    }

    @Test
    public void testNegativeBatch() {
        final String[] batch = this.generator.generateBatch(-5);
        assertNotNull("Negative batch should not be null", batch);
        assertEquals("Negative batch should have length 0", 0, batch.length);
    }
}
