package de.tasticgames.lobby.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Optional third-party integration (soft dependency). Implementations must never let a
 * missing/incompatible plugin break the lobby: every external call is guarded, failures are
 * logged once and the integration reports {@link #available()} = false afterwards.
 */
public interface Integration {

    /** Bukkit plugin name this integration hooks into (e.g. {@code LuckPerms}). */
    String pluginName();

    /** {@code true} when the plugin is present and the hook succeeded. */
    boolean available();

    /** Human readable status line for diagnostics. */
    default String status() {
        if (!available()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName());
            return plugin == null ? "not installed" : (plugin.isEnabled() ? "hook failed" : "disabled");
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName());
        return "hooked" + (plugin == null ? "" : " (" + plugin.getPluginMeta().getVersion() + ")");
    }

    static boolean pluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }
}
