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

package fr.gaetandev.antiduplication.config;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Reads {@code config.yml} once and hands out validated, clamped values.
 *
 * <p>Every getter reads a cached field rather than the configuration tree, since
 * some of them sit on the scan hot path. Values are re-read on reload.
 */
public final class ConfigManager {

    private static final List<String> LOG_LEVELS = Arrays.asList("DEBUG", "INFO", "WARN", "ERROR");

    private final AntiDuplicationPlugin plugin;
    private FileConfiguration config;

    private int scanInterval;
    private int containerInterval;
    private int batchSize;
    private long scanTimeout;

    private int uuidLength;
    private String uuidPrefix;
    private String nbtTag;

    private boolean loggingEnabled;
    private String logDirectory;
    private String logLevel;
    private int logRotationDays;

    private boolean redisEnabled;
    private String redisHost;
    private int redisPort;
    private String redisPassword;
    private int redisDatabase;
    private String redisKeyPrefix;
    private String redisServerId;
    private long redisTimeoutMillis;
    private int redisTtlSeconds;

    private boolean strictMode;
    private boolean blockNoUuid;
    private boolean monitorCreative;
    private boolean monitorAdminCommands;
    private boolean trackStackableItems;
    private int divisionTolerance;
    private int maxDivisionsPerStack;

    private int threadPoolSize;
    private boolean cacheResults;
    private long cacheTtl;

    public ConfigManager(final AntiDuplicationPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        this.plugin.saveDefaultConfig();
        this.plugin.reloadConfig();
        this.config = this.plugin.getConfig();
        this.cacheConfigValues();
        this.validateConfig();
        this.applyLogLevel();
    }

    public void reloadConfig() {
        this.loadConfig();
        this.plugin.getLogger().info("Configuration reloaded from file.");
    }

    private void cacheConfigValues() {
        // Scanning
        this.scanInterval = Math.max(1, this.config.getInt("scanning.interval", 5));
        this.containerInterval = Math.max(0, this.config.getInt("scanning.container-interval", 60));
        this.batchSize = Math.max(1, Math.min(2000, this.config.getInt("scanning.batch-size", 100)));
        this.scanTimeout = Math.max(1000L, this.config.getLong("scanning.timeout", 30000L));

        // UUID
        this.uuidLength = Math.max(8, Math.min(32, this.config.getInt("uuid.length", 12)));
        this.uuidPrefix = this.config.getString("uuid.prefix", "AD_");
        this.nbtTag = this.config.getString("uuid.nbt-tag", "AntiDupeUUID");

        // Logging
        this.loggingEnabled = this.config.getBoolean("logging.enabled", true);
        this.logDirectory = this.config.getString("logging.directory", "logs");
        this.logLevel = this.config.getString("logging.level", "INFO").toUpperCase(java.util.Locale.ROOT);
        this.logRotationDays = this.config.getInt("logging.rotation-days", 30);

        // Redis
        this.redisEnabled = this.config.getBoolean("redis.enabled", false);
        this.redisHost = this.config.getString("redis.host", "localhost");
        this.redisPort = this.config.getInt("redis.port", 6379);
        this.redisPassword = this.config.getString("redis.password", "");
        this.redisDatabase = Math.max(0, this.config.getInt("redis.database", 0));
        this.redisKeyPrefix = this.config.getString("redis.key-prefix", "antiduplication:");
        this.redisServerId = this.config.getString("redis.server-id", "");
        this.redisTimeoutMillis = Math.max(200L, this.config.getLong("redis.timeout", 2000L));
        this.redisTtlSeconds = Math.max(30, this.config.getInt("redis.ttl", 300));

        // Security
        this.strictMode = this.config.getBoolean("security.strict-mode", true);
        this.blockNoUuid = this.config.getBoolean("security.block-no-uuid", false);
        this.monitorCreative = this.config.getBoolean("security.monitor-creative", true);
        this.monitorAdminCommands = this.config.getBoolean("security.monitor-admin-commands", true);
        this.trackStackableItems = this.config.getBoolean("security.track-stackable-items", false);
        this.divisionTolerance = Math.max(1, this.config.getInt("security.division-tolerance", 30));
        this.maxDivisionsPerStack = Math.max(2, this.config.getInt("security.max-divisions-per-stack", 4));

        // Performance
        this.threadPoolSize = Math.max(1, Math.min(8, this.config.getInt("performance.thread-pool-size", 2)));
        this.cacheResults = this.config.getBoolean("performance.cache-results", true);
        this.cacheTtl = Math.max(60L, this.config.getLong("performance.cache-ttl", 300L));
    }

    private void validateConfig() {
        if (this.uuidPrefix.length() > 8) {
            this.plugin.getLogger().warning("UUID prefix is too long (max 8 characters), truncation applied.");
            this.uuidPrefix = this.uuidPrefix.substring(0, 8);
        }
        if (!LOG_LEVELS.contains(this.logLevel)) {
            this.plugin.getLogger().warning("Invalid log level: " + this.logLevel + ", defaulting to INFO.");
            this.logLevel = "INFO";
        }
        if (this.nbtTag.trim().isEmpty()) {
            this.plugin.getLogger().warning("Empty uuid.nbt-tag, defaulting to AntiDupeUUID.");
            this.nbtTag = "AntiDupeUUID";
        }
        if (this.trackStackableItems) {
            this.plugin.getLogger().warning("security.track-stackable-items is enabled (experimental): stackable "
                    + "items will carry a unique tag and therefore stop merging with each other in game, and any "
                    + "stack split the plugin does not see becomes a false positive. Only keep this on if you "
                    + "accept both trade-offs.");
        }
        if (this.blockNoUuid && this.strictMode) {
            this.plugin.getLogger().warning("security.block-no-uuid is enabled: trackable items without a UUID "
                    + "will be deleted instead of being stamped. This is destructive.");
        }
        if (this.containerInterval > 0 && this.containerInterval < this.scanInterval) {
            this.plugin.getLogger().warning("scanning.container-interval is shorter than scanning.interval; "
                    + "chunks cannot be walked more often than a scan runs, aligning them.");
            this.containerInterval = this.scanInterval;
        }
        if (this.containerInterval == 0) {
            this.plugin.getLogger().warning("scanning.container-interval is 0: containers, entities and dropped "
                    + "items are never scanned, only online players.");
        }
        if (!this.cacheResults) {
            this.plugin.getLogger().warning("performance.cache-results is disabled: the plugin can no longer tell "
                    + "an original from a copy, and will keep an arbitrary copy when a duplication is found.");
        } else if (this.containerInterval > 0 && this.cacheTtl < this.containerInterval * 2L) {
            // Items sitting in a chest are only seen on container cycles, so a TTL
            // shorter than that keeps forgetting who owns them.
            this.plugin.getLogger().warning("performance.cache-ttl (" + this.cacheTtl + "s) is short compared to "
                    + "scanning.container-interval (" + this.containerInterval + "s): stored items would keep "
                    + "losing their known owner between two chunk sweeps. Raising the TTL to "
                    + this.containerInterval * 2L + "s.");
            this.cacheTtl = this.containerInterval * 2L;
        }
    }

    /**
     * The configured level filters what the plugin sends to the console. Verbose
     * messages are re-routed through {@link AntiDuplicationPlugin#debug(String)}
     * because console handlers normally drop anything below INFO.
     */
    private void applyLogLevel() {
        final Level level;
        switch (this.logLevel) {
            case "WARN":
                level = Level.WARNING;
                break;
            case "ERROR":
                level = Level.SEVERE;
                break;
            default:
                level = Level.INFO;
                break;
        }
        this.plugin.getLogger().setLevel(level);
    }

    public int getScanInterval() {
        return this.scanInterval;
    }

    public long getScanIntervalMillis() {
        return TimeUnit.SECONDS.toMillis(this.scanInterval);
    }

    /**
     * How often the loaded chunks are walked, as opposed to the online players.
     *
     * <p>Chunks are the expensive half of a sweep and the slow moving one, while
     * players are cheap and where items actually change hands. Walking chunks on
     * every player cycle would leave a busy server permanently sweeping.
     *
     * @return the interval in seconds, or {@code 0} to never walk chunks
     */
    public int getContainerInterval() {
        return this.containerInterval;
    }

    public long getContainerIntervalMillis() {
        return TimeUnit.SECONDS.toMillis(this.containerInterval);
    }

    public boolean isContainerScanEnabled() {
        return this.containerInterval > 0;
    }

    /**
     * @return how many scan units (a player, a chunk, a world's dropped items)
     *         are processed per tick
     */
    public int getBatchSize() {
        return this.batchSize;
    }

    public long getScanTimeout() {
        return this.scanTimeout;
    }

    public int getUuidLength() {
        return this.uuidLength;
    }

    public String getUuidPrefix() {
        return this.uuidPrefix;
    }

    public String getNbtTag() {
        return this.nbtTag;
    }

    public boolean isLoggingEnabled() {
        return this.loggingEnabled;
    }

    public String getLogDirectory() {
        return this.logDirectory;
    }

    public boolean isDebugEnabled() {
        return "DEBUG".equals(this.logLevel);
    }

    public int getLogRotationDays() {
        return this.logRotationDays;
    }

    public String getLogFilenameFormat() {
        return this.config.getString("logging.filename-format", "yyyy-MM-dd");
    }

    public boolean isRedisEnabled() {
        return this.redisEnabled;
    }

    public String getRedisHost() {
        return this.redisHost;
    }

    public int getRedisPort() {
        return this.redisPort;
    }

    public String getRedisPassword() {
        return this.redisPassword;
    }

    public int getRedisDatabase() {
        return this.redisDatabase;
    }

    public String getRedisKeyPrefix() {
        return this.redisKeyPrefix;
    }

    public String getRedisServerId() {
        return this.redisServerId;
    }

    public long getRedisTimeoutMillis() {
        return this.redisTimeoutMillis;
    }

    /**
     * @return how long a shared claim survives; must comfortably exceed the scan
     *         interval or servers would keep losing their own claims
     */
    public int getRedisTtlSeconds() {
        return Math.max(this.redisTtlSeconds, this.scanInterval * 4);
    }

    public boolean isStrictMode() {
        return this.strictMode;
    }

    public boolean shouldBlockNoUuid() {
        return this.blockNoUuid;
    }

    public boolean shouldMonitorCreative() {
        return this.monitorCreative;
    }

    public boolean shouldMonitorAdminCommands() {
        return this.monitorAdminCommands;
    }

    /**
     * @return whether stackable items take part in tracking; off by default
     *         because a unique tag prevents stacks from merging
     */
    public boolean shouldTrackStackableItems() {
        return this.trackStackableItems;
    }

    public int getMaxDivisionsPerStack() {
        return this.maxDivisionsPerStack;
    }

    public int getDivisionTolerance() {
        return this.divisionTolerance;
    }

    public long getDivisionToleranceMillis() {
        return TimeUnit.SECONDS.toMillis(this.divisionTolerance);
    }

    public int getThreadPoolSize() {
        return this.threadPoolSize;
    }

    /**
     * @return whether the plugin remembers which holder a UUID belongs to, which
     *         is what allows it to keep the original copy
     */
    public boolean shouldCacheResults() {
        return this.cacheResults;
    }

    public long getCacheTtlMillis() {
        return TimeUnit.SECONDS.toMillis(this.cacheTtl);
    }
}
