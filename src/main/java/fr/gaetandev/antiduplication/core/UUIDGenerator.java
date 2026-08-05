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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces the short identifiers stamped on tracked items.
 *
 * <p>Identifiers are drawn from a 32 symbol alphabet without look-alike
 * characters, which keeps them readable in logs and in game. A bounded window of
 * the most recent values is kept so that the (astronomically unlikely) local
 * collision is caught rather than silently flagged as a duplication later on.
 */
public final class UUIDGenerator {

    private static final String ALLOWED_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CHARS_LENGTH = ALLOWED_CHARS.length();

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 32;
    private static final int MAX_CACHE_SIZE = 10_000;
    private static final int MAX_ATTEMPTS = 16;

    private final SecureRandom random = new SecureRandom();
    private final String prefix;
    private final int length;

    /**
     * Insertion ordered window of recent identifiers, evicted first-in-first-out.
     * Guarded by its own monitor: generation happens on the main thread and on
     * scanner threads alike.
     */
    private final Set<String> recentUuids = new LinkedHashSet<>();

    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();

    public UUIDGenerator(final String prefix, final int length) {
        this.prefix = prefix != null ? prefix : "";
        this.length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
    }

    public String generateUUID() {
        String uuid = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            uuid = this.prefix + this.randomString();
            if (this.remember(uuid)) {
                this.generated.incrementAndGet();
                return uuid;
            }
            this.collisions.incrementAndGet();
        }

        // Defensive only: reaching this point means the random source is broken.
        // Falling back to a monotonic suffix guarantees forward progress, at the
        // cost of a slightly longer identifier.
        final String fallback = uuid + toAlphabet(this.generated.incrementAndGet());
        this.remember(fallback);
        return fallback;
    }

    /**
     * @return {@code true} when the identifier was not already in the window
     */
    private boolean remember(final String uuid) {
        synchronized (this.recentUuids) {
            if (!this.recentUuids.add(uuid)) {
                return false;
            }
            if (this.recentUuids.size() > MAX_CACHE_SIZE) {
                final Iterator<String> oldest = this.recentUuids.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }

    private String randomString() {
        final StringBuilder sb = new StringBuilder(this.length);
        for (int i = 0; i < this.length; i++) {
            sb.append(ALLOWED_CHARS.charAt(this.random.nextInt(CHARS_LENGTH)));
        }
        return sb.toString();
    }

    private static String toAlphabet(final long value) {
        final StringBuilder sb = new StringBuilder();
        long remaining = Math.abs(value);
        do {
            sb.append(ALLOWED_CHARS.charAt((int) (remaining % CHARS_LENGTH)));
            remaining /= CHARS_LENGTH;
        } while (remaining > 0);
        return sb.toString();
    }

    public boolean isValidUUID(final String uuid) {
        if (uuid == null || uuid.isEmpty() || !uuid.startsWith(this.prefix)) {
            return false;
        }
        final String uuidPart = uuid.substring(this.prefix.length());
        if (uuidPart.length() < MIN_LENGTH || uuidPart.length() > MAX_LENGTH + 16) {
            return false;
        }
        for (int i = 0; i < uuidPart.length(); i++) {
            if (ALLOWED_CHARS.indexOf(uuidPart.charAt(i)) == -1) {
                return false;
            }
        }
        return true;
    }

    public String extractUUIDPart(final String fullUuid) {
        return this.isValidUUID(fullUuid) ? fullUuid.substring(this.prefix.length()) : null;
    }

    public String[] generateBatch(final int count) {
        if (count <= 0) {
            return new String[0];
        }
        final String[] uuids = new String[count];
        for (int i = 0; i < count; i++) {
            uuids[i] = this.generateUUID();
        }
        return uuids;
    }

    public GeneratorStats getStats() {
        final int cacheSize;
        synchronized (this.recentUuids) {
            cacheSize = this.recentUuids.size();
        }
        return new GeneratorStats(
                cacheSize,
                MAX_CACHE_SIZE,
                this.prefix,
                this.length,
                this.generated.get(),
                this.collisions.get(),
                this.collisionProbability(cacheSize)
        );
    }

    /**
     * Birthday-paradox estimate over the identifiers currently in the window.
     */
    private double collisionProbability(final int current) {
        final double totalPossible = Math.pow(CHARS_LENGTH, this.length);
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
        private final long generated;
        private final long collisions;
        private final double collisionProbability;

        GeneratorStats(final int cacheSize, final int maxCacheSize, final String prefix, final int length,
                       final long generated, final long collisions, final double collisionProbability) {
            this.cacheSize = cacheSize;
            this.maxCacheSize = maxCacheSize;
            this.prefix = prefix;
            this.length = length;
            this.generated = generated;
            this.collisions = collisions;
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

        public long getGenerated() {
            return this.generated;
        }

        public long getCollisions() {
            return this.collisions;
        }

        public double getCollisionProbability() {
            return this.collisionProbability;
        }

        @Override
        public String toString() {
            return String.format(
                    "GeneratorStats{generated: %d, retries: %d, cache: %d/%d, prefix: '%s', length: %d, collision: %.8f%%}",
                    this.generated, this.collisions, this.cacheSize, this.maxCacheSize,
                    this.prefix, this.length, this.collisionProbability * 100);
        }
    }
}
