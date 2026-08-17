package de.tasticgames.lobby.integration.placeholder;

import de.tasticgames.lobby.integration.Integration;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Registers lobby values as native TAB placeholders (usable in TAB's tablist/nametag/header
 * configs without PlaceholderAPI). Every TAB call is guarded – API versions differ between TAB
 * releases and a failing hook must never affect the lobby.
 */
public final class TabPlaceholderBridge implements PlaceholderBridge {

    private final Plugin plugin;
    private final Logger logger;
    private final List<String> registered = new CopyOnWriteArrayList<>();
    private volatile boolean available;
    private volatile boolean warned;

    public TabPlaceholderBridge(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            available = TabAPI.getInstance() != null && TabAPI.getInstance().getPlaceholderManager() != null;
        } catch (Throwable t) {
            available = false;
            logger.warning("TAB hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – TAB placeholders disabled.");
        }
    }

    public void unhook() {
        unregisterAll();
        available = false;
    }

    @Override
    public String pluginName() {
        return "TAB";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void register(String identifier, int refreshMillis, Function<Player, String> resolver) {
        if (!available) {
            return;
        }
        Objects.requireNonNull(identifier);
        Objects.requireNonNull(resolver);
        try {
            PlayerPlaceholder placeholder = TabAPI.getInstance().getPlaceholderManager().registerPlayerPlaceholder(identifier, Math.max(100, refreshMillis), tabPlayer -> {
                try {
                    Object raw = tabPlayer.getPlayer();
                    if (raw instanceof Player bukkitPlayer) {
                        String value = resolver.apply(bukkitPlayer);
                        return value == null ? "" : value;
                    }
                } catch (RuntimeException e) {
                    warnOnce("placeholder " + identifier, e);
                }
                return "";
            });
            if (placeholder != null) {
                registered.add(identifier);
            }
        } catch (Throwable t) {
            warnOnce("register " + identifier, t);
        }
    }

    @Override
    public void refresh(Player player) {
        // TAB refreshes registered player placeholders on its own interval; nothing to force here.
    }

    @Override
    public void unregisterAll() {
        for (String identifier : List.copyOf(registered)) {
            try {
                TabAPI.getInstance().getPlaceholderManager().unregisterPlaceholder(identifier);
            } catch (Throwable ignored) {
                // TAB may already be disabled
            }
        }
        registered.clear();
    }

    public int count() {
        return registered.size();
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("TAB " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
