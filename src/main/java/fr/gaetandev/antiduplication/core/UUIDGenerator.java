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

import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UUIDGenerator {

    private static final String ALLOWED_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CHARS_LENGTH = ALLOWED_CHARS.length();

    private final SecureRandom random;
    private final String prefix;
    private final int length;
    private final Set<String> recentUuids;
    private final int maxCacheSize;

    public UUIDGenerator(final String prefix, final int length) {
        this.random = new SecureRandom();
        this.prefix = prefix != null ? prefix : "";
        this.length = Math.max(4, Math.min(32, length)); // Reasonable limits
        this.maxCacheSize = Math.min(10000, (int) Math.pow(CHARS_LENGTH, this.length) / 100);
        this.recentUuids = ConcurrentHashMap.newKeySet();
        this.random.nextBytes(new byte[64]); // Warm-up
    }

    public String generateUUID() {
        String uuid;
        int attempts = 0;
        final int maxAttempts = 1000;
        do {
            uuid = this.prefix + this.generateRandomString();
            attempts++;
            if (attempts > maxAttempts) {
                uuid = this.prefix + this.generateRandomString() + "_" + System.currentTimeMillis();
                break;
            }
        } while (this.recentUuids.contains(uuid));

        this.addToCache(uuid);
        return uuid;
    }

    private String generateRandomString() {
        final StringBuilder sb = new StringBuilder(this.length);
        for (int i = 0; i < this.length; i++) {
            final int index = this.random.nextInt(CHARS_LENGTH);
            sb.append(ALLOWED_CHARS.charAt(index));
        }
        return sb.toString();
    }

    private void addToCache(final String uuid) {
        this.recentUuids.add(uuid);
        // Periodic cleanup to avoid memory bloat
        if (this.recentUuids.size() > this.maxCacheSize) {
            final int toRemove = this.maxCacheSize / 5;
            this.recentUuids.stream()
                    .limit(toRemove)
                    .forEach(this.recentUuids::remove);
        }
    }

    public boolean isValidUUID(final String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        if (!uuid.startsWith(this.prefix)) {
            return false;
        }
        final String uuidPart = uuid.substring(this.prefix.length());
        if (uuidPart.length() < 4 || uuidPart.length() > 32) {
            return false;
        }
        for (final char c : uuidPart.toCharArray()) {
            if (ALLOWED_CHARS.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }

    public String extractUUIDPart(final String fullUuid) {
        if (!this.isValidUUID(fullUuid)) {
            return null;
        }
        return fullUuid.substring(this.prefix.length());
    }

    public String[] generateBatch(final int count) {
        if (count <= 0) {
            return new String[0];
        }
        final String[] uuids = new String[count];
        final Set<String> batchSet = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < count; i++) {
            String uuid;
            do {
                uuid = this.generateUUID();
            } while (batchSet.contains(uuid));
            uuids[i] = uuid;
            batchSet.add(uuid);
        }
        return uuids;
    }

    public GeneratorStats getStats() {
        return new GeneratorStats(
                this.recentUuids.size(),
                this.maxCacheSize,
                this.prefix,
                this.length,
                this.calculateCollisionProbability()
        );
    }

    private double calculateCollisionProbability() {
        final long totalPossible = (long) Math.pow(CHARS_LENGTH, this.length);
        final int current = this.recentUuids.size();
        if (current <= 1 || totalPossible <= current) {
            return 0.0;
        }
        return 1.0 - Math.exp(-((double) current * (current - 1)) / (2.0 * totalPossible));
    }

    public static final class GeneratorStats {
        private final int cacheSize;
        private final int maxCacheSize;
        private final String prefix;
        private final int length;
        private final double collisionProbability;

        GeneratorStats(final int cacheSize, final int maxCacheSize, final String prefix, final int length, final double collisionProbability) {
            this.cacheSize = cacheSize;
            this.maxCacheSize = maxCacheSize;
            this.prefix = prefix;
            this.length = length;
            this.collisionProbability = collisionProbability;
        }

        public int getCacheSize() {
            return this.cacheSize;
        }

        public int getMaxCacheSize() {
            return this.maxCacheSize;
        }

        public String getPrefix() {
            return this.prefix;
        }

        public int getLength() {
            return this.length;
        }

        public double getCollisionProbability() {
            return this.collisionProbability;
        }

        @Override
        public String toString() {
            return String.format("GeneratorStats{cache: %d/%d, prefix: '%s', length: %d, collision: %.6f%%}",
                    this.cacheSize, this.maxCacheSize, this.prefix, this.length, this.collisionProbability * 100);
        }
    }
}
