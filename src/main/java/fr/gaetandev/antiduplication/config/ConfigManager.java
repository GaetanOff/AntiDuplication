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

import java.util.concurrent.TimeUnit;

public final class ConfigManager {

    private final AntiDuplicationPlugin plugin;
    private FileConfiguration config;
    private int scanInterval;
    private int batchSize;
    private long scanTimeout;
    private int uuidLength;
    private String uuidPrefix;
    private String nbtTag;
    private boolean loggingEnabled;
    private String logDirectory;
    private String logLevel;
    private boolean redisEnabled;
    private boolean strictMode;
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
    }

    public void reloadConfig() {
        this.loadConfig();
        this.plugin.getLogger().info("Configuration reloaded from file.");
    }

    private void cacheConfigValues() {
        // Scanning
        this.scanInterval = Math.max(1, this.config.getInt("scanning.interval", 5));
        this.batchSize = Math.max(10, this.config.getInt("scanning.batch-size", 100));
        this.scanTimeout = Math.max(5000, this.config.getLong("scanning.timeout", 30000));

        // UUID
        this.uuidLength = Math.max(8, Math.min(36, this.config.getInt("uuid.length", 12)));
        this.uuidPrefix = this.config.getString("uuid.prefix", "AD_");
        this.nbtTag = this.config.getString("uuid.nbt-tag", "AntiDupeUUID");

        // Logging
        this.loggingEnabled = this.config.getBoolean("logging.enabled", true);
        this.logDirectory = this.config.getString("logging.directory", "logs");
        this.logLevel = this.config.getString("logging.level", "INFO").toUpperCase();

        // Redis
        this.redisEnabled = this.config.getBoolean("redis.enabled", false);

        // Security
        this.strictMode = this.config.getBoolean("security.strict-mode", true);

        // Performance
        this.threadPoolSize = Math.max(1, Math.min(8, this.config.getInt("performance.thread-pool-size", 2)));
        this.cacheResults = this.config.getBoolean("performance.cache-results", true);
        this.cacheTtl = Math.max(60, this.config.getLong("performance.cache-ttl", 300));
    }

    private void validateConfig() {
        if (this.uuidPrefix.length() > 8) {
            this.plugin.getLogger().warning("UUID prefix is too long (max 8 characters), truncation applied.");
            this.uuidPrefix = this.uuidPrefix.substring(0, 8);
        }
        if (!this.isValidLogLevel(this.logLevel)) {
            this.plugin.getLogger().warning("Invalid log level: " + this.logLevel + ", defaulting to INFO.");
            this.logLevel = "INFO";
        }
        if (this.scanInterval < 1) {
            this.plugin.getLogger().warning("Scan interval too low, minimum 1 second applied.");
            this.scanInterval = 1;
        }
    }

    private boolean isValidLogLevel(final String level) {
        return level.equals("DEBUG") || level.equals("INFO") ||
                level.equals("WARN") || level.equals("ERROR");
    }

    public int getScanInterval() {
        return this.scanInterval;
    }

    public long getScanIntervalMillis() {
        return TimeUnit.SECONDS.toMillis(this.scanInterval);
    }

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

    public String getLogLevel() {
        return this.logLevel;
    }

    public int getLogRotationDays() {
        return this.config.getInt("logging.rotation-days", 30);
    }

    public boolean isRedisEnabled() {
        return this.redisEnabled;
    }

    public String getRedisHost() {
        return this.config.getString("redis.host", "localhost");
    }

    public int getRedisPort() {
        return this.config.getInt("redis.port", 6379);
    }

    public String getRedisPassword() {
        return this.config.getString("redis.password", "");
    }

    public int getRedisDatabase() {
        return this.config.getInt("redis.database", 0);
    }

    public String getRedisKeyPrefix() {
        return this.config.getString("redis.key-prefix", "antiduplication:");
    }

    public boolean isStrictMode() {
        return this.strictMode;
    }

    public boolean shouldBlockNoUuid() {
        return this.config.getBoolean("security.block-no-uuid", false);
    }

    public boolean shouldMonitorCreative() {
        return this.config.getBoolean("security.monitor-creative", true);
    }

    public boolean shouldMonitorAdminCommands() {
        return this.config.getBoolean("security.monitor-admin-commands", true);
    }

    public int getThreadPoolSize() {
        return this.threadPoolSize;
    }

    public boolean shouldCacheResults() {
        return this.cacheResults;
    }

    public long getCacheTtl() {
        return this.cacheTtl;
    }

    public long getCacheTtlMillis() {
        return TimeUnit.SECONDS.toMillis(this.cacheTtl);
    }

    public FileConfiguration getRawConfig() {
        return this.config;
    }
}
