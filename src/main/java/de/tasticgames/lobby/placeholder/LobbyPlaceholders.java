package de.tasticgames.lobby.placeholder;

import de.tasticgames.lobby.integration.placeholder.PlaceholderBridge;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Lobby values for display plugins (TAB tablist/nametags, PlaceholderAPI consumers) – the lobby renders
 * its HUD natively (boss bars) and no scoreboard. Registered as PlaceholderAPI expansion {@code tastic} (%tastic_<key>%) and,
 * when TAB is present, as native TAB placeholders (%tastic_<key>%). Values are raw text (no colours)
 * so the display plugin owns the styling; empty string when unknown.
 * <p>
 * Keys: rank, rank_display, rank_prefix, rank_suffix, language, language_code, visibility, party_size,
 * party_leader, clan, clan_tag, friends_online, online (network), server, in_open_world,
 * cookie_balance|cookies, cookie_balance_raw, cookie_cps, cookie_prestige, cookie_prestige_title,
 * cookie_lifetime, cookie_crumbs, cookie_combo, cookie_buff, cookie_generators.
 * Legacy aliases (lobby_party, lobby_clan, lobby_visibility, lobby_cookie_*) stay supported.
 */
public final class LobbyPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final Map<String, Function<Player, String>> resolvers = new LinkedHashMap<>();
    private final PlaceholderBridge bridge;

    public LobbyPlaceholders(Plugin plugin, PlaceholderBridge bridge) {
        this.plugin = Objects.requireNonNull(plugin);
        this.bridge = Objects.requireNonNull(bridge);
    }

    /** Registers a placeholder key (without prefix); call before {@link #register()} / {@link #registerBridge(int)}. */
    public LobbyPlaceholders add(String key, Function<Player, String> resolver) {
        resolvers.put(Objects.requireNonNull(key), Objects.requireNonNull(resolver));
        return this;
    }

    public List<String> keys() {
        return List.copyOf(resolvers.keySet());
    }

    /** Registers every key as native TAB placeholder %tastic_<key>% (no-op without TAB). */
    public void registerBridge(int refreshMillis) {
        if (!bridge.available()) {
            return;
        }
        for (Map.Entry<String, Function<Player, String>> entry : resolvers.entrySet()) {
            bridge.register("%tastic_" + entry.getKey() + "%", refreshMillis, entry.getValue());
        }
    }

    public String resolve(Player player, String key) {
        Function<Player, String> resolver = resolvers.get(key);
        if (resolver == null) {
            return null;
        }
        try {
            String value = resolver.apply(player);
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
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
    public @NotNull List<String> getPlaceholders() {
        return resolvers.keySet().stream().map(k -> "%tastic_" + k + "%").toList();
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (!(offlinePlayer instanceof Player player)) {
            return "";
        }
        String key = params.toLowerCase(java.util.Locale.ROOT);
        String direct = resolve(player, key);
        if (direct != null) {
            return direct;
        }
        // legacy aliases from 1.0.0
        return switch (key) {
            case "lobby_party" -> resolve(player, "party_size");
            case "lobby_clan" -> resolve(player, "clan");
            case "lobby_visibility" -> resolve(player, "visibility");
            default -> key.startsWith("lobby_cookie_") ? resolve(player, "cookie_" + key.substring("lobby_cookie_".length())) : null;
        };
    }
}
