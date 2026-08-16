package de.tasticgames.lobby.placeholder;

import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * PlaceholderAPI expansion: %tastic_lobby_party%, %tastic_lobby_clan%, %tastic_lobby_visibility%,
 * %tastic_lobby_cookie_balance%, %tastic_lobby_cookie_cps%, %tastic_lobby_cookie_prestige%.
 * Only real data; empty string when unavailable.
 */
public final class LobbyPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final SocialSnapshotService social;
    private final PlayerVisibilityService visibility;
    private final BiFunction<Player, String, String> cookiePlaceholder;

    public LobbyPlaceholders(Plugin plugin, SocialSnapshotService social, PlayerVisibilityService visibility,
                             BiFunction<Player, String, String> cookiePlaceholder) {
        this.plugin = Objects.requireNonNull(plugin);
        this.social = Objects.requireNonNull(social);
        this.visibility = Objects.requireNonNull(visibility);
        this.cookiePlaceholder = Objects.requireNonNull(cookiePlaceholder);
    }

    @Override
    public @NotNull String getIdentifier() {
        return "tastic";
    }

    @Override
    public @NotNull String getAuthor() {
        return "TasticGames";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null || !(offlinePlayer instanceof Player player)) {
            return "";
        }
        return switch (params) {
            case "lobby_party" -> social.cached(player.getUniqueId()).map(s -> s.party() == null ? "" : String.valueOf(s.party().members().size())).orElse("");
            case "lobby_clan" -> social.cached(player.getUniqueId()).map(s -> s.clan() == null ? "" : s.clan().name()).orElse("");
            case "lobby_visibility" -> visibility.modeOf(player).name();
            case "lobby_cookie_balance", "lobby_cookie_cps", "lobby_cookie_prestige" -> cookiePlaceholder.apply(player, params.substring("lobby_cookie_".length()));
            default -> null;
        };
    }
}
