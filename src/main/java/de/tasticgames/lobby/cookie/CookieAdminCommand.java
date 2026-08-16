package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieAdminMutationRequest;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.Contribution;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * /cookieadmin status <player> | addcookies|setcookies <player> <amount> | setprestige <player> <0-10> | reset <player>
 * | unlock <player> <achievement|zone> <id> | balance <player> | poi create|set <id> | zone info | setspawn
 */
public final class CookieAdminCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "tasticlobby.cookie.admin";

    private final LobbyApiService api;
    private final CookieRuntimeService runtime;
    private final CookieWorldService world;
    private final CookieClickService clicks;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final File configFile;
    private final Runnable reloadHook;
    private final Logger logger;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();

    public CookieAdminCommand(LobbyApiService api, CookieRuntimeService runtime, CookieWorldService world, CookieClickService clicks,
                              LobbyTelemetryService telemetry, MainThread mainThread, File configFile, Runnable reloadHook, Logger logger) {
        this.api = Objects.requireNonNull(api);
        this.runtime = Objects.requireNonNull(runtime);
        this.world = Objects.requireNonNull(world);
        this.clicks = Objects.requireNonNull(clicks);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.configFile = Objects.requireNonNull(configFile);
        this.reloadHook = Objects.requireNonNull(reloadHook);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "poi", "zone", "setspawn", "setcookie" -> builder(sender, args);
            case "status", "balance" -> {
                Player target = target(sender, args);
                if (target == null) return true;
                withSession(sender, target, session -> {
                    CookieProfile p = session.profile();
                    CookieStats stats = runtime.engine().compute(p);
                    line(sender, "Player", target.getName() + " prestige=" + p.prestigeLevel() + " version=" + p.version() + (p.isDirty() ? " (dirty)" : "") + (session.paused() ? " PAUSED" : ""));
                    line(sender, "Cookies", formatter.format(p.cookies()) + " lifetime=" + formatter.format(p.lifetimeCookies()) + " crumbs=" + p.crumbs());
                    line(sender, "Click", formatter.format(stats.clickValue(), Locale.ENGLISH) + " (upgrade x" + stats.upgradeClickMultiplier() + ", tree x" + stats.treeClickMultiplier() + ", buff x" + stats.buffClickMultiplier() + ")");
                    line(sender, "CPS", formatter.formatRate(stats.effectiveCps(), Locale.ENGLISH) + " base=" + formatter.formatRate(stats.baseCps(), Locale.ENGLISH)
                            + " prestige x" + stats.prestigeMultiplier() + " upgrades x" + stats.upgradeCpsMultiplier() + " tree x" + stats.treeCpsMultiplier() + " buffs x" + stats.buffCpsMultiplier());
                    if (sub.equals("balance")) {
                        for (Contribution c : stats.contributions()) {
                            line(sender, "  " + c.generatorId(), c.count() + " × " + formatter.formatRate(c.cpsEach(), Locale.ENGLISH) + " = " + formatter.formatRate(c.cpsTotal(), Locale.ENGLISH));
                        }
                        line(sender, "Offline eff.", stats.offlineEfficiency() + " golden chance x" + stats.goldenChanceMultiplier() + " value x" + stats.goldenValueMultiplier());
                    }
                    line(sender, "Generators", p.generators().toString());
                    line(sender, "Upgrades", p.upgrades().size() + " | tree " + p.prestigeUpgrades());
                    line(sender, "Achievements", p.achievements().size() + " | zones " + p.discoveredZones());
                });
            }
            case "addcookies", "setcookies", "setprestige", "reset", "unlock" -> mutate(sender, sub, args);
            default -> usage(sender);
        }
        return true;
    }

    private void mutate(CommandSender sender, String sub, String[] args) {
        Player target = target(sender, args);
        if (target == null) return;
        String kind;
        String amount = null;
        Integer prestige = null;
        String id = null;
        switch (sub) {
            case "addcookies" -> { kind = "ADD_COOKIES"; amount = arg(args, 2); }
            case "setcookies" -> { kind = "SET_COOKIES"; amount = arg(args, 2); }
            case "setprestige" -> {
                kind = "SET_PRESTIGE";
                try {
                    prestige = Integer.parseInt(arg(args, 2));
                } catch (NumberFormatException e) {
                    usage(sender);
                    return;
                }
                if (prestige < 0 || prestige > 10) {
                    sender.sendMessage(Component.text("Prestige must be 0..10", NamedTextColor.RED));
                    return;
                }
            }
            case "reset" -> kind = "RESET";
            case "unlock" -> {
                String what = arg(args, 2);
                id = arg(args, 3);
                if (what == null || id == null) {
                    usage(sender);
                    return;
                }
                kind = what.equalsIgnoreCase("zone") ? "UNLOCK_ZONE" : "UNLOCK_ACHIEVEMENT";
            }
            default -> { usage(sender); return; }
        }
        if ((kind.equals("ADD_COOKIES") || kind.equals("SET_COOKIES")) && (amount == null || !amount.matches("-?[0-9]+(\\.[0-9]+)?"))) {
            usage(sender);
            return;
        }
        String finalKind = kind;
        String finalAmount = amount;
        Integer finalPrestige = prestige;
        String finalId = id;
        UUID uuid = target.getUniqueId();
        // persist local state first so the admin mutation applies on top of the newest profile
        var session = runtime.session(uuid).orElse(null);
        var flush = session != null && session.profile().isDirty() ? runtime.save(session, true) : java.util.concurrent.CompletableFuture.completedFuture(true);
        flush.thenCompose(ok -> api.call("cookie.admin", c -> c.lobby().adminMutate(uuid,
                        new CookieAdminMutationRequest(UUID.randomUUID(), finalKind, finalAmount, finalPrestige, finalId, sender.getName()))))
                .whenComplete((response, throwable) -> mainThread.run(() -> {
                    if (throwable != null) {
                        sender.sendMessage(Component.text("Failed: " + LobbyThrowables.rootMessage(throwable), NamedTextColor.RED));
                        return;
                    }
                    runtime.applyServerState(uuid, response.profile());
                    line(sender, "Cookie admin", finalKind + " -> " + response.outcome());
                    logger.info("Cookie admin " + finalKind + " for " + target.getName() + " by " + sender.getName() + " (" + finalAmount + "/" + finalPrestige + "/" + finalId + "): " + response.outcome());
                    telemetry.event("cookie.admin_mutation", sender instanceof Player p ? p.getUniqueId() : null,
                            Map.of("kind", finalKind, "target", uuid, "amount", String.valueOf(finalAmount), "prestige", String.valueOf(finalPrestige), "id", String.valueOf(finalId)));
                }));
    }

    /** Builder helpers: write POI/zone/spawn coordinates into cookie-clicker.yml without editing Java. */
    private void builder(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            var loc = player.getLocation();
            switch (sub) {
                case "setspawn" -> writePoint(yaml, "world.entry", loc);
                case "setcookie" -> writePoint(yaml, "world.main-cookie", loc);
                case "poi" -> {
                    String id = arg(args, 2);
                    if (id == null || !id.matches("[a-z0-9_-]{1,64}")) {
                        usage(sender);
                        return;
                    }
                    writePoint(yaml, "pois." + id, loc);
                    if (!yaml.contains("pois." + id + ".radius")) yaml.set("pois." + id + ".radius", 4.0);
                    if (!yaml.contains("pois." + id + ".type")) yaml.set("pois." + id + ".type", arg(args, 3) == null ? "GENERIC" : arg(args, 3).toUpperCase(Locale.ROOT));
                }
                case "zone" -> {
                    line(sender, "Zone", String.valueOf(world.zoneAt(loc)));
                    return;
                }
                default -> { usage(sender); return; }
            }
            yaml.save(configFile);
            reloadHook.run();
            if (sub.equals("setcookie")) {
                clicks.spawnMainCookie();
            }
            line(sender, "Saved", sub + " -> cookie-clicker.yml");
        } catch (Exception e) {
            sender.sendMessage(Component.text("Failed: " + LobbyThrowables.rootMessage(e), NamedTextColor.RED));
        }
    }

    private static void writePoint(YamlConfiguration yaml, String path, org.bukkit.Location loc) {
        yaml.set(path + ".world", loc.getWorld().getName());
        yaml.set(path + ".x", loc.getX());
        yaml.set(path + ".y", loc.getY());
        yaml.set(path + ".z", loc.getZ());
        yaml.set(path + ".yaw", (double) loc.getYaw());
        yaml.set(path + ".pitch", (double) loc.getPitch());
    }

    private void withSession(CommandSender sender, Player target, java.util.function.Consumer<CookieSession> consumer) {
        runtime.load(target.getUniqueId()).whenComplete((session, throwable) -> mainThread.run(() -> {
            if (throwable != null) {
                sender.sendMessage(Component.text("Failed: " + LobbyThrowables.rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            consumer.accept(session);
        }));
    }

    private Player target(CommandSender sender, String[] args) {
        String name = arg(args, 1);
        Player target = name == null ? null : Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Component.text("Player must be online: " + name, NamedTextColor.RED));
        }
        return target;
    }

    private static String arg(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private static void line(CommandSender sender, String label, Object value) {
        sender.sendMessage(Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(String.valueOf(value), NamedTextColor.WHITE)));
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/cookieadmin status|balance <player> | addcookies|setcookies <player> <amount> | setprestige <player> <0-10> | reset <player> | unlock <player> <achievement|zone> <id> | setspawn | setcookie | poi <id> [type] | zone", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "balance", "addcookies", "setcookies", "setprestige", "reset", "unlock", "setspawn", "setcookie", "poi", "zone").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !List.of("setspawn", "setcookie", "poi", "zone").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("unlock")) {
            return List.of("achievement", "zone");
        }
        return List.of();
    }
}
