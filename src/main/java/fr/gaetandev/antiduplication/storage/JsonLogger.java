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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import fr.gaetandev.antiduplication.model.DuplicationEvent;
import fr.gaetandev.antiduplication.model.TrackedItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class JsonLogger {

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final Gson gson;
    private final Path logDirectory;

    private final BlockingQueue<LogEntry> logQueue;
    private final Thread loggerThread;
    private final AtomicBoolean running;

    private final DateTimeFormatter fileNameFormatter;
    private final DateTimeFormatter timestampFormatter;

    public JsonLogger(final AntiDuplicationPlugin plugin, final ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.logQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create();

        this.fileNameFormatter = DateTimeFormatter.ofPattern(config.getRawConfig().getString("logging.filename-format", "yyyy-MM-dd"));
        this.timestampFormatter = DateTimeFormatter.ISO_INSTANT;

        this.logDirectory = Paths.get(plugin.getDataFolder().getAbsolutePath(), config.getLogDirectory());
        this.createLogDirectory();

        this.loggerThread = new Thread(this::loggerWorker, "AntiDupe-Logger");
        this.loggerThread.setDaemon(true);
        this.loggerThread.start();

        plugin.getLogger().info("JsonLogger initialized - Directory: " + this.logDirectory.toAbsolutePath());
    }

    private void createLogDirectory() {
        try {
            Files.createDirectories(this.logDirectory);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to create log directory", e);
        }
    }

    public void logDuplicationEvent(final DuplicationEvent event) {
        if (!this.config.isLoggingEnabled()) {
            return;
        }

        try {
            final JsonObject jsonEvent = this.createDuplicationJson(event);

            final LogEntry entry = new LogEntry(
                    LogEntry.Type.DUPLICATION,
                    jsonEvent,
                    event.getDetectionTime()
            );

            if (!this.logQueue.offer(entry)) {
                this.plugin.getLogger().warning("Log queue full, event ignored");
            }

        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error while creating JSON log", e);
        }
    }

    public void logEvent(final String type, final Object data) {
        if (!this.config.isLoggingEnabled()) {
            return;
        }

        try {
            final JsonObject jsonEvent = new JsonObject();
            jsonEvent.addProperty("type", type);
            jsonEvent.addProperty("timestamp", Instant.now().toString());
            jsonEvent.add("data", this.gson.toJsonTree(data));

            final LogEntry entry = new LogEntry(
                    LogEntry.Type.GENERAL,
                    jsonEvent,
                    Instant.now()
            );

            this.logQueue.offer(entry);

        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during generic logging", e);
        }
    }

    private JsonObject createDuplicationJson(final DuplicationEvent event) {
        final JsonObject json = new JsonObject();

        // Basic information
        json.addProperty("event_type", "item_duplication");
        json.addProperty("timestamp", this.timestampFormatter.format(event.getDetectionTime()));
        json.addProperty("uuid", event.getDuplicatedUuid());
        json.addProperty("duplicate_count", event.getDuplicateCount());
        json.addProperty("severity", event.getSeverity().name());
        json.addProperty("action_taken", event.getActionTaken().name());
        json.addProperty("detection_method", event.getDetectionMethod());
        json.addProperty("scan_duration_ms", event.getScanDurationMs());

        if (event.getAdditionalInfo() != null) {
            json.addProperty("additional_info", event.getAdditionalInfo());
        }

        final JsonArray itemsArray = new JsonArray();
        for (final TrackedItem item : event.getDuplicatedItems()) {
            final JsonObject itemJson = this.createTrackedItemJson(item);
            itemsArray.add(itemJson);
        }
        json.add("duplicated_items", itemsArray);

        final JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("server_version", this.plugin.getServer().getVersion());
        serverInfo.addProperty("online_players", this.plugin.getServer().getOnlinePlayers().size());
        serverInfo.addProperty("plugin_version", this.plugin.getDescription().getVersion());
        json.add("server_info", serverInfo);

        return json;
    }

    private JsonObject createTrackedItemJson(final TrackedItem item) {
        final JsonObject json = new JsonObject();

        json.addProperty("uuid", item.getUuid());
        json.addProperty("material", item.getMaterial().name());
        json.addProperty("amount", item.getAmount());
        json.addProperty("durability", item.getDurability());
        json.addProperty("container", item.getContainer());

        if (item.getDisplayName() != null) {
            json.addProperty("display_name", item.getDisplayName());
        }

        if (item.getPlayerName() != null) {
            json.addProperty("player_name", item.getPlayerName());
        }

        if (item.getLocation() != null) {
            final JsonObject locationJson = new JsonObject();
            locationJson.addProperty("world", item.getLocation().getWorld().getName());
            locationJson.addProperty("x", item.getLocation().getBlockX());
            locationJson.addProperty("y", item.getLocation().getBlockY());
            locationJson.addProperty("z", item.getLocation().getBlockZ());
            json.add("location", locationJson);
        }

        json.addProperty("first_seen", this.timestampFormatter.format(item.getFirstSeen()));
        json.addProperty("last_seen", this.timestampFormatter.format(item.getLastSeen()));
        json.addProperty("location_description", item.getLocationDescription());

        return json;
    }

    private void loggerWorker() {
        this.plugin.getLogger().info("Logging thread started");

        while (this.running.get()) {
            try {
                final LogEntry entry = this.logQueue.take(); // Blocking
                this.writeLogEntry(entry);

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error in logging thread", e);
            }
        }

        // Empty queue before closing
        this.flushRemainingLogs();
        this.plugin.getLogger().info("Logging thread stopped");
    }

    private void writeLogEntry(final LogEntry entry) {
        try {
            final Path logFile = this.getLogFile(entry.getTimestamp());

            final String jsonLine = this.gson.toJson(entry.getJsonData()) + "\n";

            // Atomic write
            Files.write(logFile, jsonLine.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error writing to log file", e);
        }
    }

    private Path getLogFile(final Instant timestamp) {
        final LocalDate date = timestamp.atZone(ZoneId.systemDefault()).toLocalDate();
        final String fileName = date.format(this.fileNameFormatter) + ".json";
        return this.logDirectory.resolve(fileName);
    }

    private void flushRemainingLogs() {
        LogEntry entry;
        while ((entry = this.logQueue.poll()) != null) {
            try {
                this.writeLogEntry(entry);
            } catch (final Exception e) {
                this.plugin.getLogger().log(Level.WARNING, "Error while flushing logs", e);
            }
        }
    }

    public void close() {
        this.running.set(false);

        if (this.loggerThread != null) {
            this.loggerThread.interrupt();
            try {
                this.loggerThread.join(5000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        this.plugin.getLogger().info("JsonLogger closed");
    }

    public void cleanupOldLogs() {
        if (this.config.getLogRotationDays() <= 0) {
            return;
        }

        try {
            final LocalDate cutoffDate = LocalDate.now().minusDays(this.config.getLogRotationDays());

            Files.list(this.logDirectory)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> this.isOlderThan(path, cutoffDate))
                    .forEach(this::deleteLogFile);

        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during log cleanup", e);
        }
    }

    private boolean isOlderThan(final Path logFile, final LocalDate cutoffDate) {
        try {
            final String fileName = logFile.getFileName().toString();
            final String dateStr = fileName.replace(".json", "");
            final LocalDate fileDate = LocalDate.parse(dateStr, this.fileNameFormatter);
            return fileDate.isBefore(cutoffDate);
        } catch (final Exception e) {
            return false;
        }
    }

    private void deleteLogFile(final Path logFile) {
        try {
            Files.delete(logFile);
            this.plugin.getLogger().info("Old log file deleted: " + logFile.getFileName());
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to delete " + logFile, e);
        }
    }

    private static final class LogEntry {
        enum Type {
            DUPLICATION, GENERAL
        }

        private final Type type;
        private final JsonObject jsonData;
        private final Instant timestamp;

        LogEntry(final Type type, final JsonObject jsonData, final Instant timestamp) {
            this.type = type;
            this.jsonData = jsonData;
            this.timestamp = timestamp;
        }

        public Type getType() {
            return this.type;
        }

        public JsonObject getJsonData() {
            return this.jsonData;
        }

        public Instant getTimestamp() {
            return this.timestamp;
        }
    }
}