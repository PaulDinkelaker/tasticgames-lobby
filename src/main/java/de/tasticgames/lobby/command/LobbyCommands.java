package de.tasticgames.lobby.command;

import de.tasticgames.lobby.dialog.CosmeticsDialogService;
import de.tasticgames.lobby.dialog.GatewayDialogService;
import de.tasticgames.lobby.dialog.ProfileDialogService;
import de.tasticgames.lobby.dialog.SettingsDialogService;
import de.tasticgames.lobby.dialog.SocialDialogService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.world.LobbySpawnService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Player commands: /lobby, /spawn, /profile [player], /settings, /gateway, /cosmetics, /social, /cookie, /lang [de|en|hi].
 */
public final class LobbyCommands implements CommandExecutor, TabCompleter {

    private final LobbyMessages messages;
    private final LobbyPlayerService players;
    private final LobbySpawnService spawn;
    private final SettingsDialogService settings;
    private final GatewayDialogService gateway;
    private final ProfileDialogService profile;
    private final SocialDialogService social;
    private final CosmeticsDialogService cosmetics;
    private final de.tasticgames.lobby.dialog.LanguageDialogService language;
    private final LobbyApiService api;
    private final MainThread mainThread;
    private final de.tasticgames.lobby.daily.DailyDialogService daily;
    private final BiConsumer<Player, String[]> cookieCommand;
    private final Consumer<Player> leaveCookieWorld;

    public LobbyCommands(LobbyMessages messages, LobbyPlayerService players, LobbySpawnService spawn, SettingsDialogService settings,
                         GatewayDialogService gateway, ProfileDialogService profile, SocialDialogService social, CosmeticsDialogService cosmetics,
                         de.tasticgames.lobby.dialog.LanguageDialogService language, LobbyApiService api, MainThread mainThread,
                         de.tasticgames.lobby.daily.DailyDialogService daily,
                         BiConsumer<Player, String[]> cookieCommand, Consumer<Player> leaveCookieWorld) {
        this.messages = Objects.requireNonNull(messages);
        this.players = Objects.requireNonNull(players);
        this.spawn = Objects.requireNonNull(spawn);
        this.settings = Objects.requireNonNull(settings);
        this.gateway = Objects.requireNonNull(gateway);
        this.profile = Objects.requireNonNull(profile);
        this.social = Objects.requireNonNull(social);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.language = Objects.requireNonNull(language);
        this.api = Objects.requireNonNull(api);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.daily = Objects.requireNonNull(daily);
        this.cookieCommand = Objects.requireNonNull(cookieCommand);
        this.leaveCookieWorld = Objects.requireNonNull(leaveCookieWorld);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "common.players-only");
            return true;
        }
        switch (command.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "lobby", "spawn" -> {
                LobbyPlayer lobbyPlayer = players.getOrCreate(player);
                if (lobbyPlayer.inCookieWorld()) {
                    leaveCookieWorld.accept(player);
                } else {
                    spawn.teleportToSpawn(player);
                }
                messages.send(player, "lobby.spawn.teleported");
            }
            case "settings" -> settings.openMain(player);
            case "gateway" -> gateway.open(player);
            case "cosmetics" -> cosmetics.openMain(player);
            case "social" -> social.openHub(player);
            case "cookie" -> cookieCommand.accept(player, args);
            case "daily" -> daily.open(player);
            case "lang" -> {
                if (args.length == 0) {
                    language.open(player, true);
                    return true;
                }
                var selected = parseLanguage(args[0]);
                if (selected == null) {
                    messages.send(player, "language.unknown", Map.of("codes", String.join(", ", supportedLanguageCodes())));
                    return true;
                }
                if (selected == language.current(player)) {
                    messages.send(player, "language.already", Map.of("language", selected.displayName()));
                    return true;
                }
                language.select(player, selected, null, null);
            }
            case "profile" -> {
                if (args.length == 0) {
                    profile.openOwn(player);
                    return true;
                }
                String name = args[0];
                if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                    messages.send(player, "common.player-not-found", Map.of("player", name));
                    return true;
                }
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    profile.open(player, online.getUniqueId(), online.getName());
                    return true;
                }
                if (!api.enabled()) {
                    messages.send(player, "common.player-not-found", Map.of("player", name));
                    return true;
                }
                api.call("player.byName", c -> c.network().findPlayerByName(name)).whenComplete((account, t) -> mainThread.run(() -> {
                    if (t != null || account.isEmpty()) {
                        messages.send(player, "common.player-not-found", Map.of("player", name));
                        return;
                    }
                    profile.open(player, account.get().minecraftUuid(), account.get().currentName());
                }));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("profile") && args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (command.getName().equalsIgnoreCase("cookie") && args.length == 1) {
            return List.of("stats", "leaderboard", "world", "shop", "upgrades", "prestige", "menu").stream()
                    .filter(n -> n.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (command.getName().equalsIgnoreCase("lang") && args.length == 1) {
            return supportedLanguageCodes().stream().filter(n -> n.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }

    /** Accepts codes (de/en/hi), enum names and display names (deutsch, english, hindi). */
    static de.tasticgames.localization.SupportedLanguage parseLanguage(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        var byCode = de.tasticgames.localization.SupportedLanguage.find(value).orElse(null);
        if (byCode != null) return byCode;
        for (var language : de.tasticgames.localization.SupportedLanguage.values()) {
            if (language.name().equalsIgnoreCase(value) || language.displayName().equalsIgnoreCase(value)
                    || (language == de.tasticgames.localization.SupportedLanguage.GERMAN && (value.equals("deutsch") || value.equals("german")))
                    || (language == de.tasticgames.localization.SupportedLanguage.ENGLISH && (value.equals("english") || value.equals("englisch")))
                    || (language == de.tasticgames.localization.SupportedLanguage.HINDI && value.equals("hindi"))) {
                return language;
            }
        }
        return null;
    }

    private static List<String> supportedLanguageCodes() {
        return java.util.Arrays.stream(de.tasticgames.localization.SupportedLanguage.values())
                .map(l -> l.code().toLowerCase(java.util.Locale.ROOT)).toList();
    }
}
