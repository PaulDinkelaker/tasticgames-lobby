package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.locale.LobbyMessages;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Localized display names for catalog entries (falls back to the catalog id/display name). */
final class CookieNames {

    private CookieNames() {
    }

    /** Draw chances as a compact one-liner, e.g. {@code Silver 61.2%, Golden 30.6%} ({@code -} when nothing is unlocked). */
    static String specialChances(Map<SpecialCookieRarity, Double> chances) {
        return specialChances(chances, null, null);
    }

    /**
     * Draw chances with localized rarity names. {@code messages}/{@code player} may be null (admin output,
     * where the English catalog names are wanted).
     */
    static String specialChances(Map<SpecialCookieRarity, Double> chances, LobbyMessages messages, Player player) {
        if (chances.isEmpty()) {
            return "-";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            Double chance = chances.get(rarity);
            if (chance != null) {
                joiner.add(rarityName(messages, player, rarity) + " " + String.format(Locale.ROOT, "%.1f%%", chance * 100));
            }
        }
        return joiner.toString();
    }

    /** Plain (colour free) rarity name from the bundle, falling back to the catalog display name. */
    static String rarityName(LobbyMessages messages, Player player, SpecialCookieRarity rarity) {
        if (messages == null || player == null || !messages.contains(rarity.nameKey())) {
            return rarity.displayName();
        }
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.get(player, rarity.nameKey()));
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
