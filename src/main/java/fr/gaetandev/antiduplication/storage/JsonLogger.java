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
import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Writes an audit trail of duplication events as newline-delimited JSON.
 *
 * <p>One compact object per line, which is what makes the files streamable and
 * appendable — a pretty printed object spanning several lines could not be
 * appended to safely, and a single top level array would have to be rewritten on
 * every event.
 *
 * <p>Entries are queued and flushed by a dedicated thread in batches, so the game
 * threads never touch the disk. The queue is bounded: under a flood, dropping
 * entries (and saying so) is preferable to exhausting the heap.
 */
public final class JsonLogger {

    /** Bounded so a duplication storm cannot exhaust the heap. */
    private static final int QUEUE_CAPACITY = 10_000;

    /** Entries written per disk round trip. */
    private static final int FLUSH_BATCH_SIZE = 256;

    /** How long the writer waits for more entries before flushing what it has. */
    private static final long POLL_TIMEOUT_MILLIS = 500L;

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;
    private final Gson gson;
    private final Path logDirectory;

    private final BlockingQueue<LogEntry> logQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedEntries = new AtomicLong();
    private final AtomicLong writtenEntries = new AtomicLong();
    private final Thread loggerThread;

    private final DateTimeFormatter fileNameFormatter;
    private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ISO_INSTANT;

    public JsonLogger(final AntiDuplicationPlugin plugin, final ConfigManager config) {
        this.plugin = plugin;
        this.config = config;

        // Compact on purpose: one JSON document per line.
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
        this.fileNameFormatter = resolveFileNameFormatter(plugin, config.getLogFilenameFormat());
        this.logDirectory = Paths.get(plugin.getDataFolder().getAbsolutePath(), config.getLogDirectory());
        this.createLogDirectory();

        this.loggerThread = new Thread(this::loggerWorker, "AntiDupe-Logger");
        this.loggerThread.setDaemon(true);
        this.loggerThread.start();

        plugin.getLogger().info("JsonLogger writing to " + this.logDirectory.toAbsolutePath());
    }

    private static DateTimeFormatter resolveFileNameFormatter(final AntiDuplicationPlugin plugin, final String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (final IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid logging.filename-format '" + pattern
                    + "', falling back to yyyy-MM-dd");
            return DateTimeFormatter.ofPattern("yyyy-MM-dd");
        }
    }

    private void createLogDirectory() {
        try {
            Files.createDirectories(this.logDirectory);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to create the log directory", e);
        }
    }

    public void logDuplicationEvent(final DuplicationEvent event) {
        if (!this.config.isLoggingEnabled()) {
            return;
        }
        try {
            this.enqueue(new LogEntry(this.createDuplicationJson(event), event.getDetectionTime()));
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error building the duplication log entry", e);
        }
    }

    /**
     * Records a free form event, used by the security monitors.
     */
    public void logEvent(final String type, final Object data) {
        if (!this.config.isLoggingEnabled()) {
            return;
        }
        try {
            final Instant now = Instant.now();
            final JsonObject json = new JsonObject();
            json.addProperty("event_type", type);
            json.addProperty("timestamp", this.timestampFormatter.format(now));
            json.add("data", this.gson.toJsonTree(data));
            this.enqueue(new LogEntry(json, now));
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error building a log entry", e);
        }
    }

    private void enqueue(final LogEntry entry) {
        if (!this.logQueue.offer(entry)) {
            final long dropped = this.droppedEntries.incrementAndGet();
            // Only shout on the first drop and then every thousand, so a flood
            // does not turn into a console flood of its own.
            if (dropped == 1 || dropped % 1000 == 0) {
                this.plugin.getLogger().warning("Log queue saturated, " + dropped + " entries dropped so far");
            }
        }
    }

    private JsonObject createDuplicationJson(final DuplicationEvent event) {
        final JsonObject json = new JsonObject();
        json.addProperty("event_type", "item_duplication");
        json.addProperty("timestamp", this.timestampFormatter.format(event.getDetectionTime()));
        json.addProperty("uuid", event.getDuplicatedUuid());
        json.addProperty("duplicate_count", event.getDuplicateCount());
        json.addProperty("severity", event.getSeverity().name());
        json.addProperty("action_taken", event.getActionTaken().name());
        json.addProperty("detection_method", event.getDetectionMethod());
        json.addProperty("scan_duration_ms", event.getScanDurationMs());

        if (event.getAdditionalInfo() != null && !event.getAdditionalInfo().isEmpty()) {
            json.addProperty("additional_info", event.getAdditionalInfo());
        }

        final JsonArray itemsArray = new JsonArray();
        for (final TrackedItem item : event.getDuplicatedItems()) {
            final JsonObject itemJson = this.createTrackedItemJson(item);
            itemJson.addProperty("retained", item.equals(event.getRetainedItem()));
            itemsArray.add(itemJson);
        }
        json.add("duplicated_items", itemsArray);
        json.add("server_info", this.createServerInfoJson());
        return json;
    }

    private JsonObject createServerInfoJson() {
        final JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("server_version", this.plugin.getServer().getVersion());
        serverInfo.addProperty("online_players", this.plugin.getServer().getOnlinePlayers().size());
        serverInfo.addProperty("plugin_version", this.plugin.getDescription().getVersion());
        return serverInfo;
    }

    private JsonObject createTrackedItemJson(final TrackedItem item) {
        final JsonObject json = new JsonObject();
        json.addProperty("uuid", item.getUuid());
        json.addProperty("material", item.getMaterial().name());
        json.addProperty("amount", item.getAmount());
        json.addProperty("durability", item.getDurability());

        final ItemLocation location = item.getItemLocation();
        json.addProperty("location_kind", location.getKind().name());
        json.addProperty("container", item.getContainer());
        json.addProperty("location_description", item.getLocationDescription());
        if (location.getSlot() != ItemLocation.NO_SLOT) {
            json.addProperty("slot", location.getSlot());
        }
        if (item.getDisplayName() != null) {
            json.addProperty("display_name", item.getDisplayName());
        }
        if (item.getPlayerName() != null) {
            json.addProperty("player_name", item.getPlayerName());
        }
        if (location.getHolderId() != null) {
            json.addProperty("holder_id", location.getHolderId().toString());
        }
        if (location.getBlockLocation() != null && location.getBlockLocation().getWorld() != null) {
            final JsonObject locationJson = new JsonObject();
            locationJson.addProperty("world", location.getBlockLocation().getWorld().getName());
            locationJson.addProperty("x", location.getBlockLocation().getBlockX());
            locationJson.addProperty("y", location.getBlockLocation().getBlockY());
            locationJson.addProperty("z", location.getBlockLocation().getBlockZ());
            json.add("location", locationJson);
        }

        json.addProperty("first_seen", this.timestampFormatter.format(item.getFirstSeen()));
        json.addProperty("last_seen", this.timestampFormatter.format(item.getLastSeen()));
        return json;
    }

    private void loggerWorker() {
        while (this.running.get()) {
            try {
                final LogEntry first = this.logQueue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                final List<LogEntry> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
                batch.add(first);
                this.logQueue.drainTo(batch, FLUSH_BATCH_SIZE - 1);
                this.writeBatch(batch);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error in the logging thread", e);
            }
        }
        this.flushRemainingLogs();
    }

    /**
     * Groups a batch by target file so each file is opened once per flush.
     */
    private void writeBatch(final List<LogEntry> batch) {
        final Map<Path, StringBuilder> byFile = new HashMap<>();
        for (final LogEntry entry : batch) {
            byFile.computeIfAbsent(this.getLogFile(entry.timestamp), path -> new StringBuilder())
                    .append(this.gson.toJson(entry.jsonData))
                    .append(System.lineSeparator());
        }

        for (final Map.Entry<Path, StringBuilder> file : byFile.entrySet()) {
            try {
                Files.write(file.getKey(), file.getValue().toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error writing to " + file.getKey(), e);
            }
        }
        this.writtenEntries.addAndGet(batch.size());
    }

    private Path getLogFile(final Instant timestamp) {
        final LocalDate date = timestamp.atZone(ZoneId.systemDefault()).toLocalDate();
        return this.logDirectory.resolve(date.format(this.fileNameFormatter) + ".json");
    }

    private void flushRemainingLogs() {
        final List<LogEntry> remaining = new ArrayList<>();
        this.logQueue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            this.writeBatch(remaining);
        }
    }

    /**
     * Stops the writer, flushing whatever is still queued.
     */
    public void close() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }
        try {
            // The writer polls with a timeout, so it notices the flag on its own
            // and gets to flush; interrupting would abort the flush.
            this.loggerThread.join(TimeUnit.SECONDS.toMillis(5));
            if (this.loggerThread.isAlive()) {
                this.loggerThread.interrupt();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.flushRemainingLogs();

        this.plugin.getLogger().info(String.format("JsonLogger closed: %d entries written, %d dropped",
                this.writtenEntries.get(), this.droppedEntries.get()));
    }

    /**
     * Deletes log files older than {@code logging.rotation-days}.
     */
    public void cleanupOldLogs() {
        final int rotationDays = this.config.getLogRotationDays();
        if (rotationDays <= 0) {
            return;
        }

        final LocalDate cutoffDate = LocalDate.now().minusDays(rotationDays);
        try (Stream<Path> files = Files.list(this.logDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> this.isOlderThan(path, cutoffDate))
                    .forEach(this::deleteLogFile);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Error during log rotation", e);
        }
    }

    private boolean isOlderThan(final Path logFile, final LocalDate cutoffDate) {
        final String fileName = logFile.getFileName().toString();
        final String dateStr = fileName.substring(0, fileName.length() - ".json".length());
        try {
            return LocalDate.parse(dateStr, this.fileNameFormatter).isBefore(cutoffDate);
        } catch (final RuntimeException e) {
            // A file whose name is not a date is not ours to delete.
            return false;
        }
    }

    private void deleteLogFile(final Path logFile) {
        try {
            Files.delete(logFile);
            this.plugin.getLogger().info("Rotated out old log file " + logFile.getFileName());
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to delete " + logFile, e);
        }
    }

    public long getWrittenEntries() {
        return this.writtenEntries.get();
    }

    public long getDroppedEntries() {
        return this.droppedEntries.get();
    }

    public int getQueuedEntries() {
        return this.logQueue.size();
    }

    private static final class LogEntry {
        private final JsonObject jsonData;
        private final Instant timestamp;

        LogEntry(final JsonObject jsonData, final Instant timestamp) {
            this.jsonData = jsonData;
            this.timestamp = timestamp;
        }
    }
}
