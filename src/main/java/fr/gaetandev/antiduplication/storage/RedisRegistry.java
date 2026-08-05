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

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Optional shared registry that lets the servers of a network see each other's
 * item UUIDs.
 *
 * <p>Each server claims the UUIDs it observes with a short lived key. A UUID
 * claimed by another server while this one also holds it means the item exists
 * twice on the network — the cross-server duplication a single server can never
 * see on its own.
 *
 * <p>Entirely optional: when disabled, unreachable, or repeatedly failing, the
 * registry switches itself off and the plugin keeps working on local detection
 * alone.
 */
public final class RedisRegistry {

    /** Commands per pipeline round trip. */
    private static final int BATCH_SIZE = 512;

    /** Consecutive failures after which the registry gives up for this session. */
    private static final int MAX_FAILURES = 5;

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile JedisPool pool;
    private volatile String serverId;
    private volatile boolean disabled;

    public RedisRegistry(final AntiDuplicationPlugin plugin, final ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Opens the connection pool when Redis is enabled in the configuration.
     *
     * @return {@code true} when the registry is ready to use
     */
    public boolean initialize() {
        if (!this.config.isRedisEnabled()) {
            this.plugin.getLogger().fine("Shared registry disabled in the configuration");
            return false;
        }

        this.serverId = this.resolveServerId();
        try {
            final JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(Math.max(2, this.config.getThreadPoolSize() + 1));
            poolConfig.setMaxIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setBlockWhenExhausted(true);

            final String password = this.config.getRedisPassword();
            this.pool = new JedisPool(
                    poolConfig,
                    this.config.getRedisHost(),
                    this.config.getRedisPort(),
                    (int) this.config.getRedisTimeoutMillis(),
                    password == null || password.isEmpty() ? null : password,
                    this.config.getRedisDatabase(),
                    "AntiDuplication");

            try (Jedis jedis = this.pool.getResource()) {
                jedis.ping();
            }

            this.plugin.getLogger().info(String.format(
                    "Shared registry connected to %s:%d as '%s'",
                    this.config.getRedisHost(), this.config.getRedisPort(), this.serverId));
            return true;
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to reach Redis, cross-server detection is off", e);
            this.close();
            this.disabled = true;
            return false;
        }
    }

    public boolean isAvailable() {
        return this.pool != null && !this.disabled;
    }

    public String getServerId() {
        return this.serverId;
    }

    /**
     * Claims every UUID for this server and reports the ones another server
     * already holds. Performs network I/O, so it must not run on the main thread.
     *
     * @return the UUIDs currently claimed by a different server
     */
    public Set<String> claim(final Collection<String> uuids) {
        if (!this.isAvailable() || uuids.isEmpty()) {
            return java.util.Collections.emptySet();
        }

        final Set<String> foreign = new HashSet<>();
        try (Jedis jedis = this.pool.getResource()) {
            final List<String> pending = new ArrayList<>(uuids.size());
            for (final List<String> batch : partition(uuids)) {
                pending.addAll(this.claimBatch(jedis, batch));
            }
            for (final List<String> batch : partition(pending)) {
                foreign.addAll(this.resolveOwners(jedis, batch));
            }
            this.consecutiveFailures.set(0);
        } catch (final RuntimeException e) {
            this.recordFailure(e);
        }
        return foreign;
    }

    /**
     * @return the UUIDs of this batch that were already claimed by someone
     */
    private List<String> claimBatch(final Jedis jedis, final List<String> batch) {
        final SetParams params = SetParams.setParams().nx().ex(this.config.getRedisTtlSeconds());
        final Map<String, Response<String>> responses = new LinkedHashMap<>(batch.size());

        final Pipeline pipeline = jedis.pipelined();
        for (final String uuid : batch) {
            responses.put(uuid, pipeline.set(this.key(uuid), this.serverId, params));
        }
        pipeline.sync();

        final List<String> alreadyClaimed = new ArrayList<>();
        for (final Map.Entry<String, Response<String>> entry : responses.entrySet()) {
            // SET NX answers null when the key already exists.
            if (entry.getValue().get() == null) {
                alreadyClaimed.add(entry.getKey());
            }
        }
        return alreadyClaimed;
    }

    /**
     * Reads back who owns the already claimed UUIDs: this server (refresh the
     * lease) or another one (report the duplication).
     */
    private Set<String> resolveOwners(final Jedis jedis, final List<String> batch) {
        final Map<String, Response<String>> owners = new LinkedHashMap<>(batch.size());
        final Pipeline readPipeline = jedis.pipelined();
        for (final String uuid : batch) {
            owners.put(uuid, readPipeline.get(this.key(uuid)));
        }
        readPipeline.sync();

        final Set<String> foreign = new HashSet<>();
        final Pipeline refreshPipeline = jedis.pipelined();
        for (final Map.Entry<String, Response<String>> entry : owners.entrySet()) {
            final String owner = entry.getValue().get();
            if (owner == null || this.serverId.equals(owner)) {
                refreshPipeline.expire(this.key(entry.getKey()), this.config.getRedisTtlSeconds());
            } else {
                foreign.add(entry.getKey());
            }
        }
        refreshPipeline.sync();
        return foreign;
    }

    private void recordFailure(final RuntimeException error) {
        final int failures = this.consecutiveFailures.incrementAndGet();
        this.plugin.getLogger().log(Level.WARNING,
                "Shared registry error (" + failures + "/" + MAX_FAILURES + ")", error);
        if (failures >= MAX_FAILURES) {
            this.disabled = true;
            this.plugin.getLogger().severe(
                    "Shared registry disabled after repeated failures; local detection continues");
        }
    }

    private String key(final String uuid) {
        return this.config.getRedisKeyPrefix() + "item:" + uuid;
    }

    /**
     * A stable identity for this server, so its own claims are recognised across
     * restarts.
     */
    private String resolveServerId() {
        final String configured = this.config.getRedisServerId();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        final String ip = this.plugin.getServer().getIp();
        final int port = this.plugin.getServer().getPort();
        return (ip == null || ip.isEmpty() ? "server" : ip) + ':' + port;
    }

    /**
     * Splits the work into pipeline-sized batches, so a scan holding tens of
     * thousands of UUIDs does not turn into one enormous round trip.
     */
    static List<List<String>> partition(final Collection<String> values) {
        final List<List<String>> batches = new ArrayList<>();
        List<String> current = null;
        for (final String value : values) {
            if (current == null || current.size() == BATCH_SIZE) {
                current = new ArrayList<>(Math.min(BATCH_SIZE, values.size()));
                batches.add(current);
            }
            current.add(value);
        }
        return batches;
    }

    public void close() {
        final JedisPool current = this.pool;
        this.pool = null;
        if (current != null) {
            try {
                current.close();
                this.plugin.getLogger().info("Shared registry connection closed");
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Error closing the shared registry", e);
            }
        }
    }
}
