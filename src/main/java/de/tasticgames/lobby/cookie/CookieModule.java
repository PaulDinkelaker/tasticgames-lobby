package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.client.dto.lobby.CookieLeaderboardTypeResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.dialog.ProfileDialogService;
import de.tasticgames.lobby.integration.LobbyIntegrations;
import de.tasticgames.lobby.item.LobbyItemType;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.service.Service;
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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Cookie Clicker module facade: wires the domain engine with the runtime services (main cookie in
 * the lobby, special cookies, NPCs, prestige-10 open world, dialogs, admin) and exposes the
 * integration points used by the lobby (item, command, placeholders, diagnostics). The layout
 * configuration lives in a shared holder so {@code /cookieadmin} edits apply without a restart.
 */
public final class CookieModule implements Service {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final LobbyPlayerService players;
    private final LobbySpawnService spawn;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbyIntegrations integrations;
    private final Logger logger;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();
    private final AtomicReference<CookieConfiguration> configuration = new AtomicReference<>();
    private final AtomicReference<CookieProgressListener> progress = new AtomicReference<>(CookieProgressListener.NONE);

    private CookieEngine engine;
    private CookieRuntimeService runtime;
    private CookieWorldService world;
    private CookieClickService clicks;
    private SpecialCookieService special;
    private CookieNpcService npcs;
    private CookieLeaderboardService leaderboards;
    private CookieOrderService orders;
    private CookieDialogService dialogService;
    private CookieAdminCommand adminCommand;
    private final List<Service> services = new ArrayList<>();
    private final List<org.bukkit.event.Listener> listeners = new ArrayList<>();

    public CookieModule(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyApiService api, LobbyPlayerService players,
                        LobbySpawnService spawn, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry, DialogSupport dialogs,
                        MainThread mainThread, LobbyIntegrations integrations, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.players = Objects.requireNonNull(players);
        this.spawn = Objects.requireNonNull(spawn);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.integrations = Objects.requireNonNull(integrations);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cookie-module";
    }

    @Override
    public void start() throws Exception {
        CookieConfiguration loaded = loadConfiguration();
        configuration.set(loaded);
        if (loaded.legacyFile()) {
            logger.warning("config/cookie-clicker.yml uses the 1.0.0 layout (main cookie inside the cookie world). The bundled config-version "
                    + CookieConfiguration.CURRENT_VERSION + " layout (main cookie in the lobby, open world at prestige " + loaded.openWorld().requiredPrestige()
                    + ") is used instead – delete the file to regenerate it.");
        }
        engine = new CookieEngine(CookieCatalog.defaults(), loaded.balancing());
        runtime = add(new CookieRuntimeService(plugin, api, configuration::get, engine, telemetry, mainThread, logger));
        world = add(new CookieWorldService(configuration::get, runtime, players, spawn, messages, sounds, telemetry, mainThread, logger));
        clicks = add(new CookieClickService(plugin, coreApi, configuration::get, runtime, integrations.mobs(), integrations.models(), messages, sounds, telemetry, mainThread, logger));
        special = add(new SpecialCookieService(plugin, coreApi, configuration::get, runtime, world, messages, sounds, telemetry));
        npcs = add(new CookieNpcService(configuration::get, runtime, integrations.npcs(), integrations.models(), messages, sounds, telemetry, logger));
        leaderboards = add(new CookieLeaderboardService(api, Duration.ofSeconds(loaded.runtime().leaderboardCacheSeconds())));
        orders = new CookieOrderService(coreApi, runtime, messages, sounds, telemetry);
        dialogService = new CookieDialogService(coreApi, runtime, world, leaderboards, orders, messages, dialogs, mainThread, sounds, telemetry, logger);
        adminCommand = new CookieAdminCommand(api, runtime, world, clicks, telemetry, mainThread,
                new File(configurationService.configDirectory(), "cookie-clicker.yml"), this::reloadLayout, integrations.selections(),
                this::selectedCitizensNpc, logger);
        clicks.setMenuOpener(dialogService::openOverview);
        for (Service service : services) {
            service.start();
        }
        for (org.bukkit.event.Listener listener : List.of(world, clicks, special, npcs)) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            listeners.add(listener);
        }
        world.onExit(player -> special.remove(player.getUniqueId()));
        runtime.addTickListener(clicks::tickActionbar);
        runtime.setProductionListener((session, produced) -> orders.onProduced(session.player(), produced.toBigDecimal()));
        setProgressListener(progress.get()); // installs the order board as an internal progress consumer
        runtime.setProductionGate(session -> {
            Player player = Bukkit.getPlayer(session.player());
            return player != null && (clicks.inZone(player) || world.isOpenWorld(player.getWorld()));
        });
        runtime.setProductionStateListener((session, active) -> {
            Player player = Bukkit.getPlayer(session.player());
            if (player == null || session.profile().totalGenerators() == 0) return;
            boolean notify = coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(de.tasticgames.lobby.settings.LobbySettings.COOKIE_NOTIFICATIONS)).orElse(true);
            if (notify) {
                player.sendActionBar(messages.get(player, active ? "cookie.zone.entered" : "cookie.zone.left",
                        Map.of("radius", (int) configuration.get().mainCookie().zoneRadius())));
            }
        });
        logger.info("Cookie Clicker module started (" + engine.catalog().generators().size() + " generators, " + engine.catalog().upgrades().size()
                + " upgrades, prestige 0-" + engine.catalog().maxPrestigeLevel() + ", main cookie in '" + loaded.mainCookie().world() + "', open world '"
                + loaded.openWorld().name() + "' at prestige " + loaded.openWorld().requiredPrestige() + "+).");
    }

    /** Loads cookie-clicker.yml, applying in-place migrations (e.g. NPCs removed with config-version 3) and persisting them. */
    private CookieConfiguration loadConfiguration() {
        org.bukkit.configuration.file.YamlConfiguration yaml = configurationService.raw("cookie-clicker");
        List<String> changes = CookieConfiguration.migrate(yaml);
        if (!changes.isEmpty()) {
            File file = new File(configurationService.configDirectory(), "cookie-clicker.yml");
            try {
                yaml.save(file);
                logger.info("config/cookie-clicker.yml migrated: " + String.join(", ", changes) + ".");
            } catch (java.io.IOException e) {
                logger.warning("config/cookie-clicker.yml migration could not be saved (" + e.getMessage() + ") – applied in memory only: " + String.join(", ", changes));
            }
        }
        return CookieConfiguration.load(yaml, configurationService.configuration().world().name());
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

    /** Re-reads cookie-clicker.yml and re-applies the layout (main cookie, NPCs) at runtime. */
    public void reloadLayout() {
        try {
            configurationService.reload();
            CookieConfiguration fresh = loadConfiguration();
            configuration.set(fresh);
            mainThread.run(() -> {
                clicks.spawnMainCookie();
                npcs.respawn();
            });
            logger.info("Cookie layout reloaded.");
        } catch (Exception e) {
            logger.warning("Cookie layout reload failed: " + LobbyThrowables.rootMessage(e));
        }
    }

    private OptionalInt selectedCitizensNpc(Player player) {
        if (!de.tasticgames.lobby.integration.Integration.pluginEnabled("Citizens")) {
            return OptionalInt.empty();
        }
        try {
            net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getDefaultNPCSelector().getSelected(player);
            return npc == null ? OptionalInt.empty() : OptionalInt.of(npc.getId());
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    // ------------------------------------------------------------------ integration points

    /**
     * Registers the consumer of the baking progress (today the season pass) on every cookie service.
     * Must be called after {@link #start()}; the listener replaces a previously registered one.
     */
    /** Fans one progress event out to both consumers; a failing consumer never stops the other. */
    private static CookieProgressListener compose(CookieProgressListener first, CookieProgressListener second) {
        if (second == CookieProgressListener.NONE) {
            return first;
        }
        return new CookieProgressListener() {
            @Override
            public void onClick(java.util.UUID player, de.tasticgames.lobby.cookie.domain.model.CookieAmount baked) {
                first.onClick(player, baked);
                second.onClick(player, baked);
            }

            @Override
            public void onGeneratorsBought(java.util.UUID player, String generatorId, int count) {
                first.onGeneratorsBought(player, generatorId, count);
                second.onGeneratorsBought(player, generatorId, count);
            }

            @Override
            public void onUpgradeBought(java.util.UUID player, String upgradeId) {
                first.onUpgradeBought(player, upgradeId);
                second.onUpgradeBought(player, upgradeId);
            }

            @Override
            public void onPrestige(java.util.UUID player, int level) {
                first.onPrestige(player, level);
                second.onPrestige(player, level);
            }

            @Override
            public void onSpecialCookie(java.util.UUID player, de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity rarity) {
                first.onSpecialCookie(player, rarity);
                second.onSpecialCookie(player, rarity);
            }

            @Override
            public void onZoneDiscovered(java.util.UUID player, String zoneId) {
                first.onZoneDiscovered(player, zoneId);
                second.onZoneDiscovered(player, zoneId);
            }

            @Override
            public void onNpcQuestCompleted(java.util.UUID player, String questId) {
                first.onNpcQuestCompleted(player, questId);
                second.onNpcQuestCompleted(player, questId);
            }

            @Override
            public void onAchievementsUnlocked(java.util.UUID player, java.util.List<String> achievementIds) {
                first.onAchievementsUnlocked(player, achievementIds);
                second.onAchievementsUnlocked(player, achievementIds);
            }
        };
    }

    public void setProgressListener(CookieProgressListener listener) {
        progress.set(Objects.requireNonNull(listener));
        // the shift orders always listen; the external consumer (season pass) is chained behind them
        CookieProgressListener combined = orders == null ? listener : compose(orders, listener);
        clicks.setProgressListener(combined);
        dialogService.setProgressListener(combined);
        runtime.setProgressListener(combined);
        special.setProgressListener(combined);
        npcs.setProgressListener(combined);
        world.setProgressListener(combined);
    }

    public CookieConfiguration configuration() { return configuration.get(); }
    public CookieRuntimeService runtime() { return runtime; }
    public CookieWorldService world() { return world; }
    public CookieClickService clicks() { return clicks; }
    public CookieDialogService dialogs() { return dialogService; }
    public CookieAdminCommand adminCommand() { return adminCommand; }
    public CookieEngine engine() { return engine; }
    public SpecialCookieService specialCookies() { return special; }

    /** Shared cookie number formatter (short scale, localised). */
    public CookieNumberFormatter formatter() { return formatter; }

    /** Shift orders (HUD, dialogs); null before {@link #start()} ran. */
    public CookieOrderService orders() { return orders; }

    /** Lobby item handler: the cookie item opens the cookie menu. */
    public void handleItem(Player player, LobbyItemType type) {
        if (type == LobbyItemType.COOKIE) {
            dialogService.openOverview(player);
        }
    }

    /** /cookie [menu|stats|leaderboard|world|shop|upgrades|prestige] */
    public void command(Player player, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats" -> dialogService.openStats(player);
            case "leaderboard", "top" -> dialogService.openLeaderboard(player, CookieLeaderboardTypeResponse.PRESTIGE);
            case "world" -> world.enter(player);
            case "shop" -> dialogService.openShop(player);
            case "upgrades" -> dialogService.openUpgrades(player);
            case "prestige" -> dialogService.openPrestige(player);
            case "exit", "leave" -> world.leave(player);
            default -> dialogService.openOverview(player);
        }
    }

    public void leaveWorld(Player player) {
        world.leave(player);
    }

    public boolean rescue(Player player) {
        return world.rescue(player);
    }

    /** Preloads the profile after lobby init (async, silent); achievements are evaluated on the main thread. */
    public void preload(Player player) {
        if (!runtime.available()) {
            return;
        }
        runtime.load(player.getUniqueId()).whenComplete((s, t) -> {
            if (t != null) {
                Throwable cause = LobbyThrowables.unwrap(t);
                if (!(cause instanceof IllegalStateException)) { // offline during load is expected
                    logger.warning("Cookie profile preload failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(cause));
                }
                return;
            }
            mainThread.run(() -> {
                if (!player.isOnline()) return;
                List<String> unlocked = engine.evaluateAchievements(s.profile());
                unlocked.forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", CookieNames.achievement(messages, engine, player, a))));
                if (!unlocked.isEmpty()) {
                    progress.get().onAchievementsUnlocked(player.getUniqueId(), unlocked);
                }
            });
        });
    }

    public void onQuit(Player player) {
        special.forget(player.getUniqueId());
        clicks.forget(player.getUniqueId());
        orders.forget(player.getUniqueId());
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        if (lobbyPlayer != null) {
            lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
        }
        runtime.unload(player.getUniqueId());
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
                .map(r -> new ProfileDialogService.CookieSummary(r.prestigeLevel(), formatter.format(CookieAmount.parse(r.lifetimeCookies() == null ? "0" : r.lifetimeCookies())), true))
                .orElse(new ProfileDialogService.CookieSummary(0, "0", true)));
    }

    /**
     * Placeholder values (PlaceholderAPI %tastic_cookie_<key>% and TAB): balance, balance_raw, cps,
     * prestige, prestige_title, lifetime, crumbs, combo, buff, generators, in_open_world.
     */
    public String placeholder(Player player, String key) {
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            return "";
        }
        var profile = session.profile();
        return switch (key) {
            case "balance", "cookies" -> formatter.format(profile.cookies());
            case "balance_raw", "cookies_raw" -> profile.cookies().toBigDecimal().setScale(0, java.math.RoundingMode.DOWN).toPlainString();
            case "cps" -> formatter.formatRate(engine.compute(profile).effectiveCps(), Locale.ENGLISH);
            case "prestige" -> String.valueOf(profile.prestigeLevel());
            case "prestige_title" -> engine.catalog().prestige(profile.prestigeLevel()).map(p -> messages.contains(p.nameKey())
                    ? messages.raw(messages.languageOf(player), p.nameKey()) : p.displayName()).orElse("");
            case "lifetime" -> formatter.format(profile.lifetimeCookies());
            case "crumbs" -> String.valueOf(profile.crumbs());
            case "combo" -> String.valueOf(profile.comboStage());
            case "buff" -> profile.activeBuffs().stream().findFirst().map(b -> b.type().name()).orElse("");
            case "generators" -> String.valueOf(profile.totalGenerators());
            case "in_open_world" -> String.valueOf(players.find(player.getUniqueId()).map(LobbyPlayer::inCookieWorld).orElse(false));
            case "in_zone" -> String.valueOf(clicks.inZone(player));
            case "producing" -> String.valueOf(session.productionActive());
            default -> "";
        };
    }

    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        CookieConfiguration config = configuration.get();
        status.put("Cookie profiles", runtime.sessions().size() + " loaded, " + runtime.dirtyCount() + " dirty, " + runtime.savingCount() + " saving");
        status.put("Main cookie", (clicks.spawned() ? "spawned (" + clicks.backend() + ")" : "NOT SPAWNED") + " in " + config.mainCookie().world()
                + " @ " + (int) config.mainCookie().location().x() + "," + (int) config.mainCookie().location().y() + "," + (int) config.mainCookie().location().z());
        status.put("Cookie NPCs", npcs.count() + "/" + config.npcs().list().size() + " via " + npcs.backend());
        status.put("Special cookies", special.activeCount() + " spawned, every "
                + config.balancing().specialMinIntervalSeconds() / 60 + "-" + config.balancing().specialMaxIntervalSeconds() / 60 + " min per player");
        status.put("Cookie open world", !config.openWorld().enabled() ? "disabled" : (world.worldReady() ? config.openWorld().name() + " ready" : "NOT AVAILABLE")
                + ", prestige " + config.openWorld().requiredPrestige() + "+, " + config.zones().size() + " zones, " + config.pois().size() + " POIs"
                + (config.legacyFile() ? " (LEGACY cookie-clicker.yml – delete to regenerate)" : ""));
        return status;
    }

    public List<String> playerInfo(Player player) {
        return runtime.session(player.getUniqueId()).map(s -> List.of(
                "Cookie: loaded, prestige " + s.profile().prestigeLevel() + ", cookies " + formatter.format(s.profile().cookies()) + ", version " + s.profile().version()
                        + (s.profile().isDirty() ? " (dirty)" : "") + (s.paused() ? " PAUSED" : ""),
                "Cookie buffs: " + s.profile().activeBuffs().size() + ", generators " + s.profile().totalGenerators()
                        + ", open world " + (players.find(player.getUniqueId()).map(LobbyPlayer::inCookieWorld).orElse(false) ? "inside" : "outside"),
                "Cookie special: next in " + special.nextSpecialAt(player.getUniqueId())
                        .map(at -> Math.max(0, Duration.between(java.time.Instant.now(), at).toMinutes()) + " min")
                        .orElse("not scheduled") + ", " + CookieNames.specialChances(engine.rarityChances(s.profile()))))
                .orElse(List.of("Cookie: not loaded"));
    }

    /** Effective statistics of a loaded session (used by diagnostics/placeholders). */
    public CookieStats stats(CookieSession session) {
        return engine.compute(session.profile());
    }
}
