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
import fr.gaetandev.antiduplication.listeners.ItemEventListener;
import fr.gaetandev.antiduplication.storage.JsonLogger;
import fr.gaetandev.antiduplication.threading.ThreadManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class AntiDuplicationPlugin extends JavaPlugin {

    private static AntiDuplicationPlugin instance;
    private ConfigManager configManager;
    private ItemManager itemManager;
    private ThreadManager threadManager;
    private JsonLogger jsonLogger;
    private boolean pluginInitialized = false;

    @Override
    public void onEnable() {
        instance = this;
        try {
            this.initializePlugin();
            this.getLogger().info("AntiDuplication plugin enabled successfully!");
            this.getLogger().info("Version: " + this.getDescription().getVersion());
        } catch (final Exception e) {
            this.getLogger().log(Level.SEVERE, "Error during plugin initialization", e);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            this.shutdown();
            this.getLogger().info("AntiDuplication plugin disabled properly.");
        } catch (final Exception e) {
            this.getLogger().log(Level.WARNING, "Error during plugin shutdown", e);
        } finally {
            instance = null;
        }
    }

    private void initializePlugin() throws Exception {
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfig();
        this.jsonLogger = new JsonLogger(this, this.configManager);
        this.itemManager = new ItemManager(this, this.configManager, this.jsonLogger);
        this.threadManager = new ThreadManager(this, this.configManager, this.itemManager);
        this.registerCommands();
        this.registerListeners();
        this.threadManager.startTasks();
        this.threadManager.scheduleInitialScan();
        this.pluginInitialized = true;
    }

    private void registerCommands() {
        final AntiDupeCommand commandExecutor = new AntiDupeCommand(this, this.itemManager, this.threadManager);
        this.getCommand("antidupe").setExecutor(commandExecutor);
        this.getCommand("antidupe").setTabCompleter(commandExecutor);
    }

    private void registerListeners() {
        final ItemEventListener itemListener = new ItemEventListener(this, this.itemManager, this.configManager);
        this.getServer().getPluginManager().registerEvents(itemListener, this);
    }

    private void shutdown() {
        if (this.threadManager != null) {
            this.threadManager.shutdown();
        }
        if (this.jsonLogger != null) {
            this.jsonLogger.close();
        }
        this.pluginInitialized = false;
    }

    public boolean reloadConfiguration() {
        try {
            this.configManager.reloadConfig();
            if (this.threadManager != null) {
                this.threadManager.updateConfig(this.configManager);
            }
            this.getLogger().info("Configuration reloaded successfully.");
            return true;
        } catch (final Exception e) {
            this.getLogger().log(Level.SEVERE, "Error reloading configuration", e);
            return false;
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

    public boolean isPluginInitialized() {
        return this.pluginInitialized;
    }
}