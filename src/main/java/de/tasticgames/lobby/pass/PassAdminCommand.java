package de.tasticgames.lobby.pass;

import de.tasticgames.client.dto.pass.PassAdminLevelRequest;
import de.tasticgames.client.dto.pass.PassAdminXpModeResponse;
import de.tasticgames.client.dto.pass.PassAdminXpRequest;
import de.tasticgames.client.dto.pass.PassPlayerResponse;
import de.tasticgames.client.dto.pass.PassPremiumGrantRequest;
import de.tasticgames.client.dto.pass.PassSeasonImportRequest;
import de.tasticgames.client.internal.JacksonSupport;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * /passadmin status | grant &lt;player&gt; [seasonKey] | revoke &lt;player&gt; [seasonKey] |
 * addxp &lt;player&gt; &lt;amount&gt; | setlevel &lt;player&gt; &lt;level&gt; | reload | season import &lt;file&gt;
 * <p>
 * Entitlements, admin XP/level corrections and season imports are administration endpoints that
 * TasticCore's player facing pass service deliberately does not expose, so they go through the
 * lobby's own API client. Every mutation carries an operation id and is therefore safe to retry;
 * the local pass state of the target is refreshed afterwards. Season files are read relative to the
 * plugin data folder and may not escape it.
 */
public final class PassAdminCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "tasticgames.pass.admin";

    private static final List<String> SUBCOMMANDS = List.of("status", "grant", "revoke", "addxp", "setlevel", "reload", "season");

    private final LobbyApiService api;
    private final PassStateService state;
    private final PassXpService xp;
    private final LobbyMessages messages;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final File dataDirectory;
    private final Runnable reloadHook;
    private final Logger logger;

    public PassAdminCommand(LobbyApiService api, PassStateService state, PassXpService xp, LobbyMessages messages, LobbyTelemetryService telemetry,
                            MainThread mainThread, File dataDirectory, Runnable reloadHook, Logger logger) {
        this.api = Objects.requireNonNull(api);
        this.state = Objects.requireNonNull(state);
        this.xp = Objects.requireNonNull(xp);
        this.messages = Objects.requireNonNull(messages);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.reloadHook = Objects.requireNonNull(reloadHook);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "common.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                reloadHook.run();
                line(sender, "Pass reload", "config/pass.yml reloaded");
                telemetry.event("pass.admin_command", uuidOf(sender), Map.of("command", "reload"));
            }
            case "grant" -> premium(sender, args, true);
            case "revoke" -> premium(sender, args, false);
            case "addxp" -> adminXp(sender, args);
            case "setlevel" -> adminLevel(sender, args);
            case "season" -> season(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void status(CommandSender sender) {
        line(sender, "Pass", state.enabled() ? (state.available() ? "enabled, season available" : "enabled, season NOT available") : "disabled in config/pass.yml");
        state.season().ifPresent(season -> {
            line(sender, "Season", season.key() + " '" + season.displayName() + "' levels 1-" + season.maxLevel()
                    + ", xpBase " + season.xpBase() + ", xpGrowth " + season.xpGrowth());
            line(sender, "Content", season.tiers().size() + " tiers, " + season.quests().size() + " quests, " + season.dailyXpCaps().size() + " xp caps");
            line(sender, "Premium", season.premiumPriceCents() / 100.0 + " " + season.currency() + ", ends " + season.endsAt());
        });
        line(sender, "Loaded states", state.loadedCount() + " (" + xp.pendingPlayers() + " with pending progress)");
    }

    private void premium(CommandSender sender, String[] args, boolean grant) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String seasonKey = args.length >= 3 ? args[2] : null;
        resolve(sender, args[1], (uuid, name) -> {
            CompletableFuture<PassPlayerResponse> call = grant
                    ? api.call("pass.admin.grant", client -> client.pass().grantPremium(uuid, new PassPremiumGrantRequest(
                    "lobby-" + UUID.randomUUID(), "ADMIN", null, null, sender.getName(), seasonKey)))
                    : api.call("pass.admin.revoke", client -> client.pass().revokePremium(uuid, seasonKey));
            finish(sender, uuid, name, grant ? "grant" : "revoke", call);
        });
    }

    private void adminXp(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            usage(sender);
            return;
        }
        resolve(sender, args[1], (uuid, name) -> finish(sender, uuid, name, "addxp",
                api.call("pass.admin.xp", client -> client.pass().adminXp(uuid,
                        new PassAdminXpRequest(UUID.randomUUID(), amount, PassAdminXpModeResponse.ADD, sender.getName())))));
    }

    private void adminLevel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            usage(sender);
            return;
        }
        if (level < 1) {
            sender.sendMessage(Component.text("Level must be at least 1.", NamedTextColor.RED));
            return;
        }
        resolve(sender, args[1], (uuid, name) -> finish(sender, uuid, name, "setlevel",
                api.call("pass.admin.level", client -> client.pass().adminLevel(uuid,
                        new PassAdminLevelRequest(UUID.randomUUID(), level, sender.getName())))));
    }

    private void season(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("import")) {
            usage(sender);
            return;
        }
        PassSeasonImportRequest request;
        try {
            request = readSeasonFile(args[2]);
        } catch (IllegalArgumentException | IOException e) {
            sender.sendMessage(Component.text("Season file could not be read: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        api.call("pass.admin.import", client -> client.pass().importSeason(request)).whenComplete((season, throwable) -> mainThread.run(() -> {
            if (throwable != null) {
                sender.sendMessage(Component.text("Season import failed: " + LobbyThrowables.rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            line(sender, "Season import", season.key() + " (" + season.state() + ", " + season.tiers().size() + " tiers, "
                    + season.quests().size() + " quests)");
            logger.info("Pass season " + season.key() + " imported by " + sender.getName() + ".");
            telemetry.event("pass.admin_command", uuidOf(sender), Map.of("command", "season_import", "season", season.key()));
        }));
    }

    /** Reads a season JSON file below the plugin data folder; paths escaping it are rejected. */
    private PassSeasonImportRequest readSeasonFile(String name) throws IOException {
        Path base = dataDirectory.toPath().toAbsolutePath().normalize();
        Path file = base.resolve(name).toAbsolutePath().normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("path escapes the plugin data folder");
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no such file: " + base.relativize(file));
        }
        return JacksonSupport.objectMapper().readValue(file.toFile(), PassSeasonImportRequest.class);
    }

    // ------------------------------------------------------------------ helpers

    /** Resolves an online player, otherwise the account by name; reports and stops when unknown. */
    private void resolve(CommandSender sender, String name, BiConsumer<UUID, String> action) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            action.accept(online.getUniqueId(), online.getName());
            return;
        }
        if (!api.enabled()) {
            messages.send(sender, "common.player-not-found", Map.of("player", name));
            return;
        }
        api.call("player.byName", client -> client.network().findPlayerByName(name)).whenComplete((account, throwable) -> mainThread.run(() -> {
            if (throwable != null || account.isEmpty()) {
                messages.send(sender, "common.player-not-found", Map.of("player", name));
                return;
            }
            action.accept(account.get().minecraftUuid(), account.get().currentName());
        }));
    }

    /** Reports the mutation result and refreshes the local pass state of the target. */
    private void finish(CommandSender sender, UUID uuid, String name, String action, CompletableFuture<PassPlayerResponse> call) {
        call.whenComplete((player, throwable) -> mainThread.run(() -> {
            if (throwable != null) {
                sender.sendMessage(Component.text("Pass " + action + " failed: " + LobbyThrowables.rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            line(sender, "Pass " + action, name + " → level " + player.level() + "/" + player.maxLevel() + ", " + player.totalXp() + " XP, premium "
                    + player.premium());
            logger.info("Pass admin " + action + " for " + name + " by " + sender.getName() + ": level " + player.level() + ", premium " + player.premium() + ".");
            telemetry.event("pass.admin_command", uuidOf(sender), Map.of("command", action, "target", uuid));
            if (Bukkit.getPlayer(uuid) != null) {
                state.refresh(uuid); // only online targets have a local state that could be stale
            }
        }));
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/passadmin status | grant <player> [seasonKey] | revoke <player> [seasonKey] | addxp <player> <amount> "
                + "| setlevel <player> <level> | reload | season import <file>", NamedTextColor.YELLOW));
    }

    private static void line(CommandSender sender, String label, Object value) {
        sender.sendMessage(Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(String.valueOf(value), NamedTextColor.WHITE)));
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("season")) {
                return List.of("import");
            }
            if (SUBCOMMANDS.contains(sub) && !sub.equals("status") && !sub.equals("reload")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
        }
        if (args.length == 3 && sub.equals("season")) {
            return seasonFiles(args[2]);
        }
        return List.of();
    }

    private List<String> seasonFiles(String prefix) {
        String[] names = dataDirectory.list((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (names == null) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                matches.add(name);
            }
        }
        return matches;
    }
}
