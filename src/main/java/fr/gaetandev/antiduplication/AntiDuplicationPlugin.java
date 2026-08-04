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

package fr.gaetandev.antiduplication;

import fr.gaetandev.antiduplication.commands.AntiDupeCommand;
import fr.gaetandev.antiduplication.config.ConfigManager;
import fr.gaetandev.antiduplication.core.ItemManager;
import fr.gaetandev.antiduplication.listeners.AdminActionListener;
import fr.gaetandev.antiduplication.listeners.ItemEventListener;
import fr.gaetandev.antiduplication.storage.JsonLogger;
import fr.gaetandev.antiduplication.storage.RedisRegistry;
import fr.gaetandev.antiduplication.threading.ThreadManager;
import fr.gaetandev.antiduplication.utils.NBTUtils;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class AntiDuplicationPlugin extends JavaPlugin {

    private static AntiDuplicationPlugin instance;

    private ConfigManager configManager;
    private JsonLogger jsonLogger;
    private RedisRegistry redisRegistry;
    private ItemManager itemManager;
    private ThreadManager threadManager;
    private volatile boolean pluginInitialized;

    @Override
    public void onEnable() {
        instance = this;
        try {
            this.initializePlugin();
            this.getLogger().info("AntiDuplication " + this.getDescription().getVersion() + " enabled.");
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Error during plugin initialization", e);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            this.shutdown();
            this.getLogger().info("AntiDuplication disabled properly.");
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.WARNING, "Error during plugin shutdown", e);
        } finally {
            instance = null;
        }
    }

    private void initializePlugin() {
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfig();

        // Item data access is the foundation everything else stands on: without a
        // working strategy nothing can be tracked, so fail loudly and early.
        if (!NBTUtils.initialize(this)) {
            throw new IllegalStateException("No usable item data backend for this server version ("
                    + this.getServer().getVersion() + "); the plugin cannot track items");
        }
        this.getLogger().info("Item data backend: " + NBTUtils.getProviderName());

        this.jsonLogger = new JsonLogger(this, this.configManager);
        this.redisRegistry = new RedisRegistry(this, this.configManager);
        this.redisRegistry.initialize();

        this.itemManager = new ItemManager(this, this.configManager, this.jsonLogger);
        this.threadManager = new ThreadManager(this, this.configManager, this.itemManager, this.redisRegistry);
        this.threadManager.initialize();

        this.registerCommands();
        this.registerListeners();

        this.pluginInitialized = true;
        this.threadManager.startTasks();
        this.getLogger().info("Plugin initialization completed successfully");
    }

    private void registerCommands() {
        final PluginCommand command = this.getCommand("antidupe");
        if (command == null) {
            throw new IllegalStateException("Command 'antidupe' is missing from plugin.yml");
        }
        final AntiDupeCommand executor = new AntiDupeCommand(this, this.itemManager, this.threadManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        this.getServer().getPluginManager().registerEvents(
                new ItemEventListener(this, this.itemManager, this.configManager), this);
        this.getServer().getPluginManager().registerEvents(
                new AdminActionListener(this, this.configManager), this);
    }

    private void shutdown() {
        this.pluginInitialized = false;
        if (this.threadManager != null) {
            this.threadManager.shutdown();
        }
        if (this.redisRegistry != null) {
            this.redisRegistry.close();
        }
        if (this.jsonLogger != null) {
            this.jsonLogger.close();
        }
        NBTUtils.reset();
    }

    /**
     * Re-reads {@code config.yml} and applies it to the running components.
     */
    public boolean reloadConfiguration() {
        try {
            this.configManager.reloadConfig();
            if (this.threadManager != null) {
                this.threadManager.updateConfig();
            }
            this.getLogger().info("Configuration reloaded successfully.");
            return true;
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Error reloading configuration", e);
            return false;
        }
    }

    /**
     * Verbose logging gated on {@code logging.level: DEBUG}.
     *
     * <p>Routed through INFO rather than FINE because console handlers normally
     * drop anything finer, which would make the setting look broken.
     */
    public void debug(final String message) {
        if (this.configManager != null && this.configManager.isDebugEnabled()) {
            this.getLogger().info("[DEBUG] " + message);
        }
    }

    /**
     * Sends a message to every online administrator.
     */
    public void notifyAdmins(final String message) {
        for (final Player online : this.getServer().getOnlinePlayers()) {
            if (online.hasPermission("antiduplication.admin")) {
                online.sendMessage(message);
            }
        }
    }

    public static AntiDuplicationPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public ItemManager getItemManager() {
        return this.itemManager;
    }

    public ThreadManager getThreadManager() {
        return this.threadManager;
    }

    public JsonLogger getJsonLogger() {
        return this.jsonLogger;
    }

    public RedisRegistry getRedisRegistry() {
        return this.redisRegistry;
    }

    public boolean isPluginInitialized() {
        return this.pluginInitialized;
    }
}
