package de.tasticgames.lobby.integration.placeholder;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Registers lobby values as placeholders in a display plugin (TAB). PlaceholderAPI is handled by
 * {@link de.tasticgames.lobby.placeholder.LobbyPlaceholders}; this bridge makes the same values
 * available as native placeholders so TAB (and other plugins via PlaceholderAPI) can display them.
 */
public interface PlaceholderBridge extends Integration {

    /**
     * Registers a per-player placeholder. The identifier is the full placeholder including
     * percent signs (e.g. {@code %tastic_rank%}). The function is called on the plugin's refresh
     * thread and must be fast and thread-safe (return "" when unknown).
     */
    void register(String identifier, int refreshMillis, Function<Player, String> resolver);

    /** Forces a refresh of all registered placeholders for the player (no-op when unsupported). */
    void refresh(Player player);

    void unregisterAll();
}
