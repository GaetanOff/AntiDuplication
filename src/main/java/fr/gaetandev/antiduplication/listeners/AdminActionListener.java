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

package fr.gaetandev.antiduplication.listeners;

import fr.gaetandev.antiduplication.AntiDuplicationPlugin;
import fr.gaetandev.antiduplication.config.ConfigManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Audit trail for the commands that can conjure items out of nothing.
 *
 * <p>Item creation through {@code /give} and friends is legitimate but it is also
 * the cheapest way to flood an economy, and it leaves no trace in the item
 * events. Logging it gives the duplication reports the context they need: an item
 * that appears from a command is not the same problem as one that appears from an
 * exploit.
 */
public final class AdminActionListener implements Listener {

    /**
     * Command roots worth recording, matched on the first word so aliases such as
     * {@code /minecraft:give} are caught too.
     */
    private static final List<String> WATCHED_COMMANDS = Arrays.asList(
            "give", "i", "item", "kit", "enchant", "gamemode", "gm", "gmc", "more", "repair", "spawnmob");

    private final AntiDuplicationPlugin plugin;
    private final ConfigManager config;

    public AdminActionListener(final AntiDuplicationPlugin plugin, final ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(final PlayerCommandPreprocessEvent event) {
        this.record(event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(final ServerCommandEvent event) {
        this.record("CONSOLE", event.getCommand());
    }

    private void record(final String sender, final String rawCommand) {
        if (!this.config.shouldMonitorAdminCommands() || rawCommand == null) {
            return;
        }

        final String root = commandRoot(rawCommand);
        if (root == null || !WATCHED_COMMANDS.contains(root)) {
            return;
        }

        this.plugin.getJsonLogger().logEvent("admin_command",
                String.format("Sender: %s, Command: %s", sender, rawCommand.trim()));
        this.plugin.debug("Item related command from " + sender + ": " + rawCommand.trim());
    }

    /**
     * @return the command name in lower case, namespace and slash stripped
     */
    static String commandRoot(final String rawCommand) {
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        final int firstSpace = command.indexOf(' ');
        if (firstSpace >= 0) {
            command = command.substring(0, firstSpace);
        }
        final int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        return command.isEmpty() ? null : command.toLowerCase(Locale.ROOT);
    }
}
