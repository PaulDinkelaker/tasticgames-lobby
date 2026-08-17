package de.tasticgames.lobby.command;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.music.MusicService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerInitializationService;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import de.tasticgames.lobby.world.LobbySpawnService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * /tasticlobby status|reload|player <name>|setspawn|build|cosmetic grant|revoke|inspect <player> <id>|catalog
 */
public final class TasticLobbyCommand implements CommandExecutor, TabCompleter {

    public static final String PERM_ADMIN = "tasticlobby.admin";
    public static final String PERM_STATUS = "tasticlobby.status";
    public static final String PERM_RELOAD = "tasticlobby.reload";
    public static final String PERM_BUILD = "tasticlobby.build";
    public static final String PERM_SETSPAWN = "tasticlobby.setspawn";
    public static final String PERM_COSMETIC_ADMIN = "tasticlobby.cosmetic.admin";

    private final TasticLobbyPlugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final LobbyPlayerService players;
    private final LobbyPlayerInitializationService initialization;
    private final LobbySpawnService spawn;
    private final LobbyItemService items;
    private final PlayerVisibilityService visibility;
    private final CosmeticService cosmetics;
    private final MusicService music;
    private final LobbyTelemetryService telemetry;
    private final LobbyMessages messages;
    private final MainThread mainThread;
    private final Instant startedAt = Instant.now();
    private final Supplier<Map<String, String>> extraStatus;
    private final Function<Player, List<String>> extraPlayerInfo;
    private final Runnable reloadHook;
    private final de.tasticgames.lobby.integration.selection.SelectionProvider selections;

    public TasticLobbyCommand(TasticLobbyPlugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyApiService api,
                              LobbyPlayerService players, LobbyPlayerInitializationService initialization, LobbySpawnService spawn, LobbyItemService items,
                              PlayerVisibilityService visibility, CosmeticService cosmetics, MusicService music,
                              LobbyTelemetryService telemetry, LobbyMessages messages, MainThread mainThread,
                              Supplier<Map<String, String>> extraStatus, Function<Player, List<String>> extraPlayerInfo, Runnable reloadHook,
                              de.tasticgames.lobby.integration.selection.SelectionProvider selections) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.players = Objects.requireNonNull(players);
        this.initialization = Objects.requireNonNull(initialization);
        this.spawn = Objects.requireNonNull(spawn);
        this.items = Objects.requireNonNull(items);
        this.visibility = Objects.requireNonNull(visibility);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.music = Objects.requireNonNull(music);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.messages = Objects.requireNonNull(messages);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.extraStatus = Objects.requireNonNull(extraStatus);
        this.extraPlayerInfo = Objects.requireNonNull(extraPlayerInfo);
        this.reloadHook = Objects.requireNonNull(reloadHook);
        this.selections = Objects.requireNonNull(selections);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "status" -> {
                if (!permit(sender, PERM_STATUS)) return true;
                status(sender);
            }
            case "reload" -> {
                if (!permit(sender, PERM_RELOAD)) return true;
                try {
                    configurationService.reload();
                    reloadHook.run();
                    line(sender, "Reload", "configuration and messages reloaded (worlds/services untouched)");
                    telemetry.event("lobby.admin_command", sender instanceof Player p ? p.getUniqueId() : null, Map.of("command", "reload"));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Reload failed: " + LobbyThrowables.rootMessage(e), NamedTextColor.RED));
                }
            }
            case "player" -> {
                if (!permit(sender, PERM_ADMIN)) return true;
                if (args.length < 2) {
                    usage(sender);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    messages.send(sender, "common.player-not-found", Map.of("player", args[1]));
                    return true;
                }
                playerInfo(sender, target);
            }
            case "setspawn" -> {
                if (!permit(sender, PERM_SETSPAWN) || !requirePlayer(sender)) return true;
                Player player = (Player) sender;
                try {
                    spawn.setSpawn(player.getLocation());
                    messages.send(sender, "lobby.admin.spawn_set");
                    telemetry.event("lobby.admin_command", player.getUniqueId(), Map.of("command", "setspawn"));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Could not save spawn: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "build" -> {
                if (!permit(sender, PERM_BUILD) || !requirePlayer(sender)) return true;
                toggleBuild((Player) sender);
            }
            case "region" -> {
                if (!permit(sender, PERM_SETSPAWN) || !requirePlayer(sender)) return true;
                region((Player) sender, args);
            }
            case "cosmetic" -> {
                if (!permit(sender, PERM_COSMETIC_ADMIN)) return true;
                cosmetic(sender, args);
            }
            default -> usage(sender);
        }
        return true;
    }

    private void toggleBuild(Player player) {
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        boolean enable = !lobbyPlayer.buildMode();
        lobbyPlayer.buildMode(enable);
        if (enable) {
            items.clearItems(player);
            player.setGameMode(GameMode.CREATIVE);
            messages.send(player, "lobby.admin.build_enabled");
        } else {
            player.setGameMode(GameMode.ADVENTURE);
            initialization.applyLobbyState(player, lobbyPlayer, false);
            messages.send(player, "lobby.admin.build_disabled");
        }
        telemetry.event("lobby.admin_command", player.getUniqueId(), Map.of("command", "build", "enabled", enable));
    }

    private void cosmetic(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("catalog")) {
            line(sender, "Catalog", cosmetics.catalog().size() + " cosmetics");
            cosmetics.catalog().all().forEach(d -> line(sender, d.id(), d.category() + " " + d.rarity() + " unlock=" + d.unlockSource() + (d.enabled() ? "" : " (disabled)")));
            return;
        }
        if (args.length < 3) {
            usage(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "common.player-not-found", Map.of("player", args[2]));
            return;
        }
        switch (action) {
            case "inspect" -> cosmetics.load(target.getUniqueId()).whenComplete((c, t) -> mainThread.run(() -> {
                if (t != null) {
                    sender.sendMessage(Component.text("Lookup failed: " + LobbyThrowables.rootMessage(t), NamedTextColor.RED));
                    return;
                }
                line(sender, "Owned", String.join(", ", c.owned()));
                line(sender, "Equipped", c.equipped().toString());
            }));
            case "grant", "revoke" -> {
                if (args.length < 4) {
                    usage(sender);
                    return;
                }
                String id = args[3].toLowerCase(java.util.Locale.ROOT);
                if (cosmetics.catalog().find(id).isEmpty()) {
                    sender.sendMessage(Component.text("Unknown cosmetic id: " + id, NamedTextColor.RED));
                    return;
                }
                var future = action.equals("grant") ? cosmetics.unlock(target.getUniqueId(), id, "ADMIN") : cosmetics.revoke(target.getUniqueId(), id);
                telemetry.event("lobby.admin_command", sender instanceof Player p ? p.getUniqueId() : null,
                        Map.of("command", "cosmetic." + action, "target", target.getUniqueId(), "cosmetic", id));
                future.whenComplete((r, t) -> mainThread.run(() -> {
                    if (t != null) {
                        sender.sendMessage(Component.text("Failed: " + LobbyThrowables.rootMessage(t), NamedTextColor.RED));
                    } else {
                        line(sender, "Cosmetic " + action, id + " -> " + r.outcome());
                        plugin.getLogger().info("Cosmetic " + action + " " + id + " for " + target.getName() + " by " + sender.getName() + ": " + r.outcome());
                    }
                }));
            }
            default -> usage(sender);
        }
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("── TasticLobby " + plugin.getPluginMeta().getVersion() + " ──", NamedTextColor.GOLD));
        line(sender, "Uptime", formatDuration(Duration.between(startedAt, Instant.now())));
        line(sender, "Core", "connected, " + coreApi.playerManager().onlinePlayers().size() + " loaded players, " + coreApi.settingRegistry().size() + " settings");
        line(sender, "API", !api.enabled() ? "DISABLED (degraded)" : api.healthy() ? "OK" : "DEGRADED (" + api.consecutiveFailures() + " failures: " + api.lastFailure() + ")");
        line(sender, "Lobby players", players.size() + " loaded, " + players.initializedCount() + " initialized");
        line(sender, "Music", music.activeSessions() + " music sessions");
        line(sender, "Cosmetics", cosmetics.catalog().size() + " in catalog, renderers " + cosmetics.renderers().stream().map(r -> r.id()).toList());
        line(sender, "Telemetry", "queue=" + telemetry.queueSize() + " published=" + telemetry.published() + " dropped=" + telemetry.dropped());
        line(sender, "World", configurationService.configuration().world().name() + (Bukkit.getWorld(configurationService.configuration().world().name()) == null ? " (NOT LOADED)" : " loaded"));
        extraStatus.get().forEach((k, v) -> line(sender, k, v));
    }

    private void playerInfo(CommandSender sender, Player target) {
        LobbyPlayer lobbyPlayer = players.getOrCreate(target);
        var tastic = coreApi.playerManager().find(target.getUniqueId()).orElse(null);
        sender.sendMessage(Component.text("── " + target.getName() + " ──", NamedTextColor.GOLD));
        line(sender, "UUID", target.getUniqueId().toString());
        line(sender, "Initialized", lobbyPlayer.initialized() + ", TasticPlayer ready: " + (tastic != null && tastic.ready()));
        line(sender, "Language", tastic == null ? "-" : tastic.language());
        line(sender, "Mode", lobbyPlayer.mode() + ", build=" + lobbyPlayer.buildMode() + ", world=" + target.getWorld().getName());
        line(sender, "Visibility", visibility.modeOf(target).name());
        line(sender, "Cosmetics", cosmetics.cached(target.getUniqueId()).map(c -> c.equipped().toString()).orElse("not loaded"));
        line(sender, "Zone/POI", lobbyPlayer.currentZoneId() + " / " + lobbyPlayer.currentPoiId());
        for (String extra : extraPlayerInfo.apply(target)) {
            sender.sendMessage(Component.text(extra, NamedTextColor.GRAY));
        }
    }

    private boolean permit(CommandSender sender, String permission) {
        if (sender.hasPermission(PERM_ADMIN) || sender.hasPermission(permission)) {
            return true;
        }
        messages.send(sender, "common.no-permission");
        return false;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        messages.send(sender, "common.players-only");
        return false;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/tasticlobby status|reload|player <name>|setspawn|build|cosmetic <catalog|inspect|grant|revoke> [player] [id]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/tasticlobby region <teleport|launchpad> <add|remove> <id> [vx vy vz]  (WorldEdit selection)", NamedTextColor.YELLOW));
    }

    /**
     * Writes launchpad / teleport-pad regions from the player's WorldEdit selection into lobby.yml
     * (movement.teleport-pads.regions.<id> / movement.launchpads.directional.<id>) and reloads.
     */
    private void region(Player player, String[] args) {
        String kind = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        String action = args.length > 2 ? args[2].toLowerCase(java.util.Locale.ROOT) : "";
        String id = args.length > 3 ? args[3].toLowerCase(java.util.Locale.ROOT) : null;
        if (!(kind.equals("teleport") || kind.equals("launchpad")) || !(action.equals("add") || action.equals("remove")) || id == null || !id.matches("[a-z0-9_-]{1,64}")) {
            usage(player);
            return;
        }
        String base = kind.equals("teleport") ? "movement.teleport-pads.regions." + id : "movement.launchpads.directional." + id;
        try {
            java.io.File file = new java.io.File(configurationService.configDirectory(), "lobby.yml");
            org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            if (action.equals("remove")) {
                yaml.set(base, null);
            } else {
                var region = selections.selection(player).orElse(null);
                if (region == null) {
                    player.sendMessage(Component.text("Make a WorldEdit selection first (//wand)" + (selections.available() ? "" : " - WorldEdit/FAWE is not installed") + ".", NamedTextColor.RED));
                    return;
                }
                yaml.set(base + ".world", region.world());
                yaml.set(base + ".min.x", region.minX());
                yaml.set(base + ".min.y", region.minY());
                yaml.set(base + ".min.z", region.minZ());
                yaml.set(base + ".max.x", region.maxX());
                yaml.set(base + ".max.y", region.maxY());
                yaml.set(base + ".max.z", region.maxZ());
                if (kind.equals("launchpad")) {
                    double vx = args.length > 4 ? Double.parseDouble(args[4]) : 0.0;
                    double vy = args.length > 5 ? Double.parseDouble(args[5]) : 1.4;
                    double vz = args.length > 6 ? Double.parseDouble(args[6]) : 0.0;
                    yaml.set(base + ".velocity.x", vx);
                    yaml.set(base + ".velocity.y", vy);
                    yaml.set(base + ".velocity.z", vz);
                }
            }
            yaml.save(file);
            configurationService.reload();
            reloadHook.run();
            line(player, "Saved", kind + " region '" + id + "' -> lobby.yml (reloaded)");
            telemetry.event("lobby.admin_command", player.getUniqueId(), Map.of("command", "region", "kind", kind, "action", action, "id", id));
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Velocity must be numeric: vx vy vz", NamedTextColor.RED));
        } catch (Exception e) {
            player.sendMessage(Component.text("Could not save region: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private static void line(CommandSender sender, String label, Object value) {
        sender.sendMessage(Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(String.valueOf(value), NamedTextColor.WHITE)));
    }

    static String formatDuration(Duration d) {
        long s = d.getSeconds();
        return String.format("%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "reload", "player", "setspawn", "build", "cosmetic", "region").stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("region")) {
            if (args.length == 2) return List.of("teleport", "launchpad");
            if (args.length == 3) return List.of("add", "remove");
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cosmetic")) {
            return List.of("catalog", "inspect", "grant", "revoke");
        }
        if (args.length == 2 || (args.length == 3 && args[0].equalsIgnoreCase("cosmetic"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("cosmetic")) {
            return cosmetics.catalog().all().stream().map(d -> d.id()).filter(s -> s.startsWith(args[3].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }
}
