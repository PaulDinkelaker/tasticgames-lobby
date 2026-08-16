package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.client.dto.lobby.CookieLeaderboardTypeResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.dialog.ProfileDialogService;
import de.tasticgames.lobby.hud.LobbyHudService;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.item.LobbyItemType;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Cookie Clicker module facade: wires the domain engine with the runtime services and exposes
 * the integration points used by the lobby (items, HUD, commands, placeholders, diagnostics).
 */
public final class CookieModule implements Service {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final LobbyPlayerService players;
    private final LobbyItemService items;
    private final LobbySpawnService spawn;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbyHudService hud;
    private final Logger logger;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();

    private CookieConfiguration configuration;
    private CookieEngine engine;
    private CookieRuntimeService runtime;
    private CookieWorldService world;
    private CookieClickService clicks;
    private GoldenCookieService golden;
    private CookieNpcService npcs;
    private CookieLeaderboardService leaderboards;
    private CookieDialogService dialogService;
    private CookieAdminCommand adminCommand;
    private final List<Service> services = new ArrayList<>();
    private final List<org.bukkit.event.Listener> listeners = new ArrayList<>();

    public CookieModule(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyApiService api, LobbyPlayerService players,
                        LobbyItemService items, LobbySpawnService spawn, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry,
                        DialogSupport dialogs, MainThread mainThread, LobbyHudService hud, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.players = Objects.requireNonNull(players);
        this.items = Objects.requireNonNull(items);
        this.spawn = Objects.requireNonNull(spawn);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.hud = Objects.requireNonNull(hud);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cookie-module";
    }

    @Override
    public void start() throws Exception {
        configuration = CookieConfiguration.load(configurationService.raw("cookie-clicker"), configurationService.configuration().world().name());
        engine = new CookieEngine(CookieCatalog.defaults(), configuration.balancing());
        runtime = add(new CookieRuntimeService(plugin, api, configuration, engine, telemetry, mainThread, logger));
        world = add(new CookieWorldService(configuration, runtime, players, items, spawn, messages, sounds, telemetry, mainThread, logger));
        clicks = add(new CookieClickService(plugin, coreApi, configuration, runtime, world, players, messages, sounds, telemetry, logger));
        golden = add(new GoldenCookieService(plugin, coreApi, configuration, runtime, world, players, messages, sounds, telemetry, logger));
        npcs = add(new CookieNpcService(plugin, configuration, runtime, world, messages, sounds, telemetry, logger));
        leaderboards = add(new CookieLeaderboardService(api, Duration.ofSeconds(configuration.runtime().leaderboardCacheSeconds())));
        dialogService = new CookieDialogService(runtime, world, leaderboards, messages, dialogs, mainThread, sounds, telemetry, logger);
        adminCommand = new CookieAdminCommand(api, runtime, world, clicks, telemetry, mainThread,
                new File(configurationService.configDirectory(), "cookie-clicker.yml"), this::reloadLayout, logger);
        for (Service service : services) {
            service.start();
        }
        for (org.bukkit.event.Listener listener : List.of(world, clicks, golden, npcs)) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            listeners.add(listener);
        }
        world.onExit(player -> golden.remove(player.getUniqueId()));
        hud.setCookieLines(this::hudLines);
        runtime.addTickListener(session -> {
            Player player = Bukkit.getPlayer(session.player());
            if (player != null && players.getOrCreate(player).inCookieWorld() && session.profile().totalGenerators() > 0) {
                CookieStats stats = engine.compute(session.profile());
                long now = System.currentTimeMillis();
                if (now - session.lastActionbarAt() > 4000) {
                    session.lastActionbarAt(now);
                    boolean hudOn = coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.COOKIE_HUD)).orElse(true);
                    if (hudOn) {
                        player.sendActionBar(messages.get(player, "cookie.click.actionbar", Map.of("gain", "0",
                                "cookies", formatter.format(session.profile().cookies()), "cps", formatter.formatRate(stats.effectiveCps(), Locale.ENGLISH), "combo", Component.empty())));
                    }
                }
            }
        });
        logger.info("Cookie Clicker module started (" + engine.catalog().generators().size() + " generators, " + engine.catalog().upgrades().size()
                + " upgrades, prestige 0-" + engine.catalog().maxPrestigeLevel() + ", " + engine.catalog().zones().size() + " zones).");
    }

    private <T extends Service> T add(T service) {
        services.add(service);
        return service;
    }

    @Override
    public void stop() {
        for (org.bukkit.event.Listener listener : listeners) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        for (int i = services.size() - 1; i >= 0; i--) {
            try {
                services.get(i).stop();
            } catch (Exception e) {
                logger.warning("Cookie service " + services.get(i).id() + " failed to stop: " + e.getMessage());
            }
        }
        services.clear();
    }

    private void reloadLayout() {
        try {
            configurationService.reload();
            configuration = CookieConfiguration.load(configurationService.raw("cookie-clicker"), configurationService.configuration().world().name());
        } catch (Exception e) {
            logger.warning("Cookie layout reload failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ integration points

    public CookieRuntimeService runtime() { return runtime; }
    public CookieWorldService world() { return world; }
    public CookieDialogService dialogs() { return dialogService; }
    public CookieAdminCommand adminCommand() { return adminCommand; }
    public CookieEngine engine() { return engine; }

    /** Lobby item handler for cookie-related items. */
    public void handleItem(Player player, LobbyItemType type) {
        switch (type) {
            case COOKIE, COOKIE_STATS -> dialogService.openOverview(player);
            case COOKIE_SHOP -> dialogService.openShop(player);
            case COOKIE_UPGRADES -> dialogService.openUpgrades(player);
            case COOKIE_PRESTIGE -> dialogService.openPrestige(player);
            case COOKIE_TRAVEL -> dialogService.openTravel(player);
            case COOKIE_EXIT -> world.leave(player);
            default -> { }
        }
    }

    /** /cookie [stats|leaderboard|world|shop|prestige] */
    public void command(Player player, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats" -> dialogService.openStats(player);
            case "leaderboard", "top" -> dialogService.openLeaderboard(player, CookieLeaderboardTypeResponse.PRESTIGE);
            case "world" -> world.enter(player);
            case "shop" -> dialogService.openShop(player);
            case "prestige" -> dialogService.openPrestige(player);
            default -> dialogService.openOverview(player);
        }
    }

    public void leaveWorld(Player player) {
        world.leave(player);
    }

    public boolean rescue(Player player) {
        return world.rescue(player);
    }

    /** Preloads the profile after lobby init (async, silent). */
    public void preload(Player player) {
        if (runtime.available()) {
            runtime.load(player.getUniqueId()).whenComplete((s, t) -> {
                if (t == null) {
                    List<String> unlocked = engine.evaluateAchievements(s.profile());
                    if (!unlocked.isEmpty()) {
                        mainThread.run(() -> unlocked.forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", world.achievementName(player, a)))));
                    }
                }
            });
        }
    }

    public void onQuit(Player player) {
        golden.remove(player.getUniqueId());
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        if (lobbyPlayer != null) {
            lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
        }
        runtime.unload(player.getUniqueId());
    }

    public List<Component> hudLines(Player player) {
        boolean show = configurationService.configuration().hud().showCookieLine()
                && coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.COOKIE_HUD)).orElse(true);
        if (!show) {
            return List.of();
        }
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            return List.of();
        }
        var lang = messages.languageOf(player);
        Locale locale = lang == de.tasticgames.localization.SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
        CookieStats stats = engine.compute(session.profile());
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get(lang, "lobby.hud.cookie.prestige", Map.of("prestige", session.profile().prestigeLevel())));
        lines.add(messages.get(lang, "lobby.hud.cookie.cookies", Map.of("cookies", formatter.format(session.profile().cookies(), locale))));
        if (players.getOrCreate(player).inCookieWorld()) {
            lines.add(messages.get(lang, "lobby.hud.cookie.cps", Map.of("cps", formatter.formatRate(stats.effectiveCps(), locale))));
            session.profile().activeBuffs().stream().findFirst().ifPresent(buff -> lines.add(messages.get(lang, "lobby.hud.cookie.buff",
                    Map.of("buff", buff.type().name(), "seconds", Math.max(0, Duration.between(java.time.Instant.now(), buff.expiresAt()).getSeconds())))));
        }
        return lines;
    }

    public CompletableFuture<ProfileDialogService.CookieSummary> profileSummary(UUID uuid) {
        CookieSession session = runtime.session(uuid).orElse(null);
        if (session != null) {
            return CompletableFuture.completedFuture(new ProfileDialogService.CookieSummary(session.profile().prestigeLevel(), formatter.format(session.profile().lifetimeCookies()), true));
        }
        if (!api.enabled()) {
            return CompletableFuture.completedFuture(new ProfileDialogService.CookieSummary(0, "0", false));
        }
        return api.call("cookie.profile", c -> c.lobby().findCookieProfile(uuid)).thenApply(optional -> optional
                .map(r -> new ProfileDialogService.CookieSummary(r.prestigeLevel(), formatter.format(de.tasticgames.lobby.cookie.domain.model.CookieAmount.parse(r.lifetimeCookies() == null ? "0" : r.lifetimeCookies())), true))
                .orElse(new ProfileDialogService.CookieSummary(0, "0", true)));
    }

    public String placeholder(Player player, String key) {
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            return "";
        }
        return switch (key) {
            case "balance" -> formatter.format(session.profile().cookies());
            case "cps" -> formatter.formatRate(engine.compute(session.profile()).effectiveCps(), Locale.ENGLISH);
            case "prestige" -> String.valueOf(session.profile().prestigeLevel());
            default -> "";
        };
    }

    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("Cookie profiles", runtime.sessions().size() + " loaded, " + runtime.dirtyCount() + " dirty, " + runtime.savingCount() + " saving");
        status.put("Cookie world", (world.worldReady() ? configuration.world().name() + " ready" : "NOT AVAILABLE") + ", " + configuration.zones().size() + " zones, "
                + configuration.pois().size() + " POIs, " + npcs.count() + " NPCs, " + golden.activeCount() + " golden cookies");
        return status;
    }

    public List<String> playerInfo(Player player) {
        return runtime.session(player.getUniqueId()).map(s -> List.of(
                "Cookie: loaded, prestige " + s.profile().prestigeLevel() + ", cookies " + formatter.format(s.profile().cookies()) + ", version " + s.profile().version()
                        + (s.profile().isDirty() ? " (dirty)" : "") + (s.paused() ? " PAUSED" : ""),
                "Cookie buffs: " + s.profile().activeBuffs().size() + ", generators " + s.profile().totalGenerators()))
                .orElse(List.of("Cookie: not loaded"));
    }
}
