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
 * Player commands: /lobby, /spawn, /profile [player], /settings, /gateway, /cosmetics, /social, /cookie.
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
    private final LobbyApiService api;
    private final MainThread mainThread;
    private final BiConsumer<Player, String[]> cookieCommand;
    private final Consumer<Player> leaveCookieWorld;

    public LobbyCommands(LobbyMessages messages, LobbyPlayerService players, LobbySpawnService spawn, SettingsDialogService settings,
                         GatewayDialogService gateway, ProfileDialogService profile, SocialDialogService social, CosmeticsDialogService cosmetics,
                         LobbyApiService api, MainThread mainThread, BiConsumer<Player, String[]> cookieCommand, Consumer<Player> leaveCookieWorld) {
        this.messages = Objects.requireNonNull(messages);
        this.players = Objects.requireNonNull(players);
        this.spawn = Objects.requireNonNull(spawn);
        this.settings = Objects.requireNonNull(settings);
        this.gateway = Objects.requireNonNull(gateway);
        this.profile = Objects.requireNonNull(profile);
        this.social = Objects.requireNonNull(social);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.api = Objects.requireNonNull(api);
        this.mainThread = Objects.requireNonNull(mainThread);
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
            return List.of("stats", "leaderboard", "world", "shop", "prestige").stream()
                    .filter(n -> n.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }
}
