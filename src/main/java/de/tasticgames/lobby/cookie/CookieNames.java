package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.locale.LobbyMessages;
import org.bukkit.entity.Player;

/** Localized display names for catalog entries (falls back to the catalog id/display name). */
final class CookieNames {

    private CookieNames() {
    }

    static String achievement(LobbyMessages messages, CookieEngine engine, Player player, String id) {
        return engine.catalog().achievement(id)
                .map(a -> messages.contains(a.nameKey()) ? messages.raw(messages.languageOf(player), a.nameKey()) : id)
                .orElse(id);
    }

    static String zone(LobbyMessages messages, CookieEngine engine, Player player, String zoneId) {
        return engine.catalog().zone(zoneId)
                .map(z -> messages.contains(z.nameKey()) ? messages.raw(messages.languageOf(player), z.nameKey()) : z.displayName())
                .orElse(zoneId);
    }
}
