package de.tasticgames.lobby.item;

import java.util.Locale;
import java.util.Optional;

/**
 * Logical lobby items (slots/materials come from items.yml).
 */
public enum LobbyItemType {
    GATEWAY, PROFILE, SOCIAL, COOKIE, COSMETICS, SETTINGS, VISIBILITY;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LobbyItemType> find(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
