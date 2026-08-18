package de.tasticgames.lobby.bootstrap;

import de.tasticgames.TasticCorePlugin;
import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.command.LobbyCommands;
import de.tasticgames.lobby.command.TasticLobbyCommand;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.cookie.CookieModule;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.dialog.CosmeticsDialogService;
import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.dialog.GatewayDialogService;
import de.tasticgames.lobby.dialog.LanguageDialogService;
import de.tasticgames.lobby.dialog.ProfileDialogService;
import de.tasticgames.lobby.dialog.SettingsDialogService;
import de.tasticgames.lobby.dialog.SocialDialogService;
import de.tasticgames.lobby.dialog.WelcomeDialogService;
import de.tasticgames.lobby.gateway.GatewayService;
import de.tasticgames.lobby.integration.LobbyIntegrations;
import de.tasticgames.lobby.integration.rank.RankProvider;
import de.tasticgames.lobby.item.LobbyItemListener;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.item.LobbyItemType;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.movement.MovementListener;
import de.tasticgames.lobby.music.MusicService;
import de.tasticgames.lobby.npc.LobbyNpcService;
import de.tasticgames.lobby.pass.PassModule;
import de.tasticgames.lobby.placeholder.LobbyPlaceholders;
import de.tasticgames.lobby.player.LobbyConnectionListener;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerInitializationService;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.player.LobbySettingChangeListener;
import de.tasticgames.lobby.settings.LobbySettingsRegistrar;
import de.tasticgames.lobby.social.NetworkNotifier;
import de.tasticgames.lobby.social.SocialActionService;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.lobby.world.VoidRescueListener;
import de.tasticgames.lobby.world.WorldEnvironmentService;
import de.tasticgames.lobby.world.WorldProtectionListener;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Composition root of TasticLobby: services are started in dependency order and stopped in
 * reverse; a startup failure stops what already runs. Business logic lives in the services.
 */
public final class LobbyBootstrap {

    private final TasticLobbyPlugin plugin;
    private final Logger logger;
    private final Deque<Service> started = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();
    private BukkitTask positionSampler;
    private LobbyPlaceholders placeholders;
    private boolean placeholderApiRegistered;

    private TasticCoreApi coreApi;
    private LobbyConfigurationService configurationService;
    private LobbyIntegrations integrations;
    private LobbyApiService api;
    private LobbyMessages messages;
    private LobbyPlayerService players;
    private LobbyTelemetryService telemetry;
    private SocialSnapshotService social;
    private LobbySpawnService spawn;
    private WorldEnvironmentService environment;
    private PlayerVisibilityService visibility;
    private LobbyItemService items;
    private LobbyPlayerInitializationService initialization;
    private CosmeticService cosmetics;
    private MusicService music;
    private GatewayService gateway;
    private SocialActionService socialActions;
    private CookieModule cookie;
    private PassModule pass;
    private LobbyNpcService serviceNpcs;
    private de.tasticgames.lobby.hud.HudService hud;
    private de.tasticgames.lobby.placeholder.HudContextProvider hudContext;

    public LobbyBootstrap(TasticLobbyPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void start() throws Exception {
        long startNanos = System.nanoTime();
        coreApi = TasticCorePlugin.instance().api();
        logger.info("Connected to TasticCore API (" + coreApi.settingRegistry().size() + " settings, " + coreApi.playerManager().onlinePlayers().size() + " loaded players).");

        MainThread mainThread = new MainThread(plugin);
        LobbySounds sounds = new LobbySounds(coreApi);
        DialogSupport dialogs = new DialogSupport(mainThread);

        configurationService = start(new LobbyConfigurationService(plugin));
        integrations = start(new LobbyIntegrations(plugin, logger));
        start(new LobbySettingsRegistrar(coreApi, logger));
        messages = start(new LobbyMessages(plugin, coreApi, logger));
        dialogs.setErrorHandler((player, throwable) -> {
            logger.warning("Dialog action failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
            messages.send(player, "common.error");
        });
        api = start(new LobbyApiService(configurationService, logger));
        players = start(new LobbyPlayerService());
        telemetry = start(new LobbyTelemetryService(plugin, configurationService, api, logger));
        social = start(new SocialSnapshotService(api));
        spawn = start(new LobbySpawnService(configurationService, players, logger));
        Set<String> managedWorlds = new HashSet<>();
        managedWorlds.add(configurationService.configuration().world().name());
        environment = start(new WorldEnvironmentService(plugin, configurationService, () -> managedWorlds, logger));
        visibility = start(new PlayerVisibilityService(plugin, coreApi, social));
        items = start(new LobbyItemService(plugin, configurationService, messages, coreApi, visibility::modeOf, integrations.customItems()));
        initialization = start(new LobbyPlayerInitializationService(coreApi, configurationService, players, spawn, items, visibility, telemetry, logger));
        cosmetics = start(new CosmeticService(plugin, coreApi, configurationService, api, telemetry, mainThread, integrations, logger));
        music = start(new MusicService(plugin, coreApi, configurationService, players, logger));
        gateway = start(new GatewayService(plugin, api, telemetry, logger));
        NetworkNotifier notifier = new NetworkNotifier(api);
        socialActions = start(new SocialActionService(api, social, notifier, messages, sounds, mainThread, telemetry, logger));

        // dialogs
        LanguageDialogService languageDialog = new LanguageDialogService(coreApi, messages, dialogs, mainThread, logger);
        SettingsDialogService settingsDialog = new SettingsDialogService(coreApi, messages, dialogs, mainThread, sounds, languageDialog, logger);
        GatewayDialogService gatewayDialog = new GatewayDialogService(gateway, social, messages, dialogs, mainThread, sounds, telemetry, logger);
        ProfileDialogService profileDialog = new ProfileDialogService(coreApi, social, socialActions, cosmetics, messages, dialogs, mainThread, telemetry, logger);
        SocialDialogService socialDialog = new SocialDialogService(api, social, socialActions, profileDialog, messages, dialogs, mainThread, telemetry, logger);
        CosmeticsDialogService cosmeticsDialog = new CosmeticsDialogService(cosmetics, messages, dialogs, mainThread, sounds, telemetry, logger);
        WelcomeDialogService welcomeDialog = new WelcomeDialogService(coreApi, api, messages, dialogs, mainThread, gatewayDialog::open, logger);
        profileDialog.setCosmeticsOpener(cosmeticsDialog::openMain);

        // cookie clicker (main cookie in the lobby, open world at prestige 10)
        cookie = start(new CookieModule(plugin, coreApi, configurationService, api, players, spawn, messages, sounds, telemetry, dialogs, mainThread, integrations, logger));
        managedWorlds.add(cookie.configuration().openWorld().name());
        if (!cookie.configuration().mainCookie().world().equals(configurationService.configuration().world().name())) {
            managedWorlds.add(cookie.configuration().mainCookie().world());
        }
        environment.apply();
        profileDialog.setCookieSummary(cookie::profileSummary);

        // season pass: state cache over TasticCore, Cookie Clicker XP hooks, dialogs, level-up feedback
        pass = start(new PassModule(plugin, coreApi, configurationService, api, messages, sounds, telemetry, dialogs, mainThread, logger));
        cookie.setProgressListener(pass.progressListener());
        profileDialog.setPassLine(pass::profileLine);
        RankProvider ranks = integrations.ranks();
        profileDialog.setRankResolver(p -> ranks.rank(p).displayName());
        socialActions.setAfterAction(visibility::apply);

        // lobby service NPCs (pass, game modes, games & events) – independent of the cookie quest NPCs
        serviceNpcs = start(new LobbyNpcService(configurationService, integrations.npcs(), gateway, messages, sounds, telemetry, mainThread,
                () -> pass.configuration().npcEnabled(), pass::openOverview, cookie.dialogs()::openOverview, logger));
        register(serviceNpcs);

        // cosmetic asset packs (ItemsAdder content + HMCCosmetics definitions) dropped in by the operator
        org.bukkit.configuration.file.YamlConfiguration cosmeticsYaml = configurationService.raw("cosmetics");
        de.tasticgames.lobby.cosmetic.pack.CosmeticPackInstaller cosmeticPacks =
                start(new de.tasticgames.lobby.cosmetic.pack.CosmeticPackInstaller(plugin, integrations.customItems(),
                        cosmeticsYaml.getBoolean("packs.enabled", true),
                        cosmeticsYaml.getBoolean("packs.reload-hmccosmetics", true), logger));

        // native top-screen HUD (boss bars) fed by the context provider
        hudContext = new de.tasticgames.lobby.placeholder.HudContextProvider(coreApi, messages, items, players, ranks, social, gateway, cosmetics, visibility, cookie, pass,
                player -> formatTicks(player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)),
                player -> players.find(player.getUniqueId()).map(lp -> formatDuration(java.time.Duration.between(lp.joinedAt(), java.time.Instant.now()))).orElse(""));
        hud = start(new de.tasticgames.lobby.hud.HudService(plugin, coreApi, configurationService, hudContext, integrations.customItems(), players,
                p -> ranks.rank(p).displayName(), player -> formatTicks(player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)),
                () -> Bukkit.getOnlinePlayers().size(), cosmeticPacks::installedContent, logger));
        items.setProfileHeadModel(hud::profileHeadModel);
        socialActions.setAfterAction(p -> {
            visibility.apply(p);
            hud.refresh(p);
        });

        // post-init hooks: music, cosmetics, social snapshot, cookie preload
        initialization.addPostInitHook(music::play);
        initialization.addPostInitHook(hud::show);
        initialization.addPostInitHook(player -> {
            if (cosmetics.available()) {
                cosmetics.load(player.getUniqueId()).whenComplete((c, t) -> mainThread.run(() -> {
                    if (t == null && player.isOnline()) {
                        cosmetics.render(player);
                    }
                }));
            }
        });
        initialization.addPostInitHook(player -> {
            if (social.available()) {
                social.load(player.getUniqueId(), true).whenComplete((s, t) -> mainThread.run(() -> {
                    if (t == null && player.isOnline()) {
                        visibility.applyAll();
                    }
                }));
            }
        });
        initialization.addPostInitHook(cookie::preload);
        initialization.addPostInitHook(pass::preload);
        integrations.customItems().onReady(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.find(player.getUniqueId()).filter(LobbyPlayer::initialized).ifPresent(lp -> items.refresh(player, lp));
            }
        });

        // listeners
        LobbyConnectionListener connection = new LobbyConnectionListener(coreApi, configurationService, players, spawn, initialization, languageDialog, welcomeDialog, telemetry, logger);
        languageDialog.setOnSelected(connection::continueAfterLanguage);
        connection.addQuitHook(cookie::onQuit);
        connection.addQuitHook(pass::onQuit);
        connection.addQuitHook(music::stop);
        connection.addQuitHook(hud::hide);
        connection.addQuitHook(cosmetics::clear);
        connection.addQuitHook(p -> social.invalidate(p.getUniqueId()));
        connection.addQuitHook(p -> gateway.forget(p.getUniqueId()));
        connection.addQuitHook(p -> welcomeDialog.forget(p.getUniqueId()));
        register(connection);
        register(new WorldProtectionListener(environment, players));
        register(new VoidRescueListener(configurationService, environment, spawn, players, telemetry, cookie::rescue));
        register(new MovementListener(configurationService, environment, spawn, players, coreApi, sounds, telemetry));
        register(new LobbyItemListener(items, players, environment, configurationService, sounds, telemetry, (player, type) -> handleItem(player, type,
                gatewayDialog, profileDialog, socialDialog, cosmeticsDialog, settingsDialog)));
        register(new LobbySettingChangeListener(players, music, visibility, items, cosmetics, hud));

        // commands
        LobbyCommands commands = new LobbyCommands(messages, players, spawn, settingsDialog, gatewayDialog, profileDialog, socialDialog, cosmeticsDialog,
                languageDialog, api, mainThread, cookie::command, cookie::leaveWorld);
        for (String name : List.of("lobby", "spawn", "profile", "settings", "gateway", "cosmetics", "social", "cookie", "lang")) {
            var command = plugin.getCommand(name);
            if (command == null) {
                logger.warning("Command '" + name + "' is missing from plugin.yml – not registered.");
                continue;
            }
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        TasticLobbyCommand admin = new TasticLobbyCommand(plugin, coreApi, configurationService, api, players, initialization, spawn, items, visibility, cosmetics,
                music, telemetry, messages, mainThread, this::extraStatus, cookie::playerInfo, this::applyReload, integrations.selections());
        plugin.getCommand("tasticlobby").setExecutor(admin);
        plugin.getCommand("tasticlobby").setTabCompleter(admin);
        plugin.getCommand("cookieadmin").setExecutor(cookie.adminCommand());
        plugin.getCommand("cookieadmin").setTabCompleter(cookie.adminCommand());
        plugin.getCommand("pass").setExecutor(pass.command());
        plugin.getCommand("pass").setTabCompleter(pass.command());
        plugin.getCommand("passadmin").setExecutor(pass.adminCommand());
        plugin.getCommand("passadmin").setTabCompleter(pass.adminCommand());

        // placeholders for PlaceholderAPI consumers and TAB
        placeholders = buildPlaceholders(ranks);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                placeholders.register();
                placeholderApiRegistered = true;
                logger.info("Registered PlaceholderAPI expansion 'tastic' (" + placeholders.keys().size() + " placeholders).");
            } catch (Throwable t) {
                logger.warning("PlaceholderAPI expansion could not be registered: " + LobbyThrowables.rootMessage(t));
            }
        }
        placeholders.registerBridge(Math.max(250, configurationService.raw("lobby").getInt("placeholders.refresh-millis", 1000)));

        // position sampling (heatmap foundation)
        int sample = Math.max(2, configurationService.configuration().telemetry().positionSampleSeconds());
        positionSampler = Bukkit.getScheduler().runTaskTimer(plugin, this::samplePositions, 20L * sample, 20L * sample);

        // players already online (reload / late enable)
        for (Player player : Bukkit.getOnlinePlayers()) {
            coreApi.playerManager().find(player.getUniqueId()).filter(p -> p.ready()).ifPresent(p -> initialization.initialize(player, p));
        }
        logger.info("TasticLobby bootstrap finished in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms (" + started.size() + " services, " + listeners.size() + " listeners).");
    }

    public void stop() throws Exception {
        if (positionSampler != null) {
            positionSampler.cancel();
        }
        if (placeholders != null) {
            try {
                if (placeholderApiRegistered) {
                    placeholders.unregister();
                }
            } catch (Throwable ignored) {
                // PlaceholderAPI may already be disabled
            }
        }
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        Exception failure = null;
        while (!started.isEmpty()) {
            Service service = started.pop();
            try {
                service.stop();
            } catch (Exception e) {
                logger.warning("Service " + service.id() + " failed to stop: " + LobbyThrowables.rootMessage(e));
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ------------------------------------------------------------------ helpers

    private LobbyPlaceholders buildPlaceholders(RankProvider ranks) {
        LobbyPlaceholders p = new LobbyPlaceholders(plugin, integrations.tab());
        p.add("rank", player -> ranks.rank(player).group());
        p.add("rank_display", player -> ranks.rank(player).displayName());
        p.add("rank_prefix", player -> ranks.rank(player).prefix());
        p.add("rank_suffix", player -> ranks.rank(player).suffix());
        p.add("language", player -> messages.languageOf(player).displayName());
        p.add("language_code", player -> messages.languageOf(player).code());
        p.add("visibility", player -> visibility.modeOf(player).name());
        p.add("server", player -> api.serverId());
        p.add("party_size", player -> social.cached(player.getUniqueId()).map(s -> s.party() == null ? "0" : String.valueOf(s.party().members().size())).orElse(""));
        p.add("party_leader", player -> social.cached(player.getUniqueId()).map(s -> s.party() == null ? "" : s.party().members().stream()
                .filter(m -> m.leader()).map(m -> m.name()).findFirst().orElse("")).orElse(""));
        p.add("clan", player -> social.cached(player.getUniqueId()).map(s -> s.clan() == null ? "" : s.clan().name()).orElse(""));
        p.add("clan_tag", player -> social.cached(player.getUniqueId()).map(s -> s.clan() == null ? "" : s.clan().name().length() <= 5 ? s.clan().name()
                : s.clan().name().substring(0, 4).toUpperCase(java.util.Locale.ROOT)).orElse(""));
        p.add("friends_online", player -> social.cached(player.getUniqueId()).map(s -> String.valueOf(s.friendUuids().stream().filter(s::online).count())).orElse(""));
        p.add("friends", player -> social.cached(player.getUniqueId()).map(s -> String.valueOf(s.friendUuids().size())).orElse(""));
        p.add("online", player -> String.valueOf(Bukkit.getOnlinePlayers().size()));
        p.add("kills", player -> String.valueOf(player.getStatistic(org.bukkit.Statistic.PLAYER_KILLS)));
        p.add("deaths", player -> String.valueOf(player.getStatistic(org.bukkit.Statistic.DEATHS)));
        p.add("playtime", player -> formatTicks(player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)));
        p.add("playtime_hours", player -> String.valueOf(player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 72_000L));
        p.add("playtime_minutes", player -> String.valueOf(player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 1_200L % 60));
        p.add("session_playtime", player -> players.find(player.getUniqueId()).map(lp -> formatDuration(java.time.Duration.between(lp.joinedAt(), java.time.Instant.now()))).orElse(""));
        p.add("held_item", player -> items.typeOf(player.getInventory().getItemInMainHand()).map(t -> t.name()).orElse("NONE"));
        p.add("held_slot", player -> String.valueOf(player.getInventory().getHeldItemSlot()));
        p.add("cookies", player -> cookie.placeholder(player, "balance"));
        p.add("cps", player -> cookie.placeholder(player, "cps"));
        p.add("prestige", player -> cookie.placeholder(player, "prestige"));
        p.add("cookie_in_zone", player -> cookie.placeholder(player, "in_zone"));
        p.add("cookie_producing", player -> cookie.placeholder(player, "producing"));
        p.add("in_open_world", player -> cookie.placeholder(player, "in_open_world"));
        for (String key : List.of("balance", "balance_raw", "cookies", "cookies_raw", "cps", "prestige", "prestige_title", "lifetime", "crumbs", "combo", "buff", "generators")) {
            p.add("cookie_" + key, player -> cookie.placeholder(player, key));
        }
        for (String key : List.of("level", "xp", "xp_next", "progress", "premium", "season", "quests_done", "claimable", "total_xp")) {
            p.add("pass_" + key, player -> pass.placeholder(player, key));
        }
        hudContext.registerInto(p);
        return p;
    }

    private static String formatTicks(long ticks) {
        return formatDuration(java.time.Duration.ofSeconds(ticks / 20L));
    }

    private static String formatDuration(java.time.Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private void handleItem(Player player, LobbyItemType type, GatewayDialogService gatewayDialog, ProfileDialogService profileDialog,
                            SocialDialogService socialDialog, CosmeticsDialogService cosmeticsDialog, SettingsDialogService settingsDialog) {
        switch (type) {
            case GATEWAY -> gatewayDialog.open(player);
            case PROFILE -> profileDialog.openOwn(player);
            case SOCIAL -> socialDialog.openHub(player);
            case COSMETICS -> cosmeticsDialog.openMain(player);
            case SETTINGS -> settingsDialog.openMain(player);
            case VISIBILITY -> visibility.cycle(player).whenComplete((mode, t) -> {
                if (t == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            messages.send(player, "lobby.visibility.changed", Map.of("mode",
                                    messages.get(player, "lobby.visibility." + mode.name().toLowerCase(java.util.Locale.ROOT))));
                        }
                    });
                }
            });
            default -> cookie.handleItem(player, type);
        }
    }

    private Map<String, String> extraStatus() {
        Map<String, String> status = new java.util.LinkedHashMap<>();
        status.put("API credentials", api.credentialSource() + (api.credentialsRejected() ? " (REJECTED by the API – check service key)" : "")
                + (api.apiOutdated() ? " (API OUTDATED – deploy tasticgames-api 1.0)" : ""));
        status.putAll(integrations.status());
        status.put("HUD", hud.configuration() == null || !hud.configuration().enabled() ? "disabled" : hud.activeHuds() + " active, boss bars"
                + (integrations.customItems().available() ? ", ItemsAdder icons" + (hud.boxesAvailable() ? " + boxes" : " (boxes missing – run /iazip)") : ", unicode icons"));
        status.putAll(cookie.status());
        status.put("Service NPCs", serviceNpcs.status());
        status.putAll(pass.status());
        return status;
    }

    private void applyReload() {
        try {
            messages.stop();
            messages.start();
            environment.apply();
            cookie.reloadLayout();
            pass.reload();
            serviceNpcs.reload();
            hud.reload();
            for (Player player : Bukkit.getOnlinePlayers()) {
                LobbyPlayer lobbyPlayer = players.getOrCreate(player);
                items.refresh(player, lobbyPlayer);
            }
        } catch (Exception e) {
            logger.warning("Reload post-processing failed: " + LobbyThrowables.rootMessage(e));
        }
    }

    private void samplePositions() {
        if (!api.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
            if (lobbyPlayer == null || !environment.isManaged(player.getWorld())) {
                continue;
            }
            var loc = player.getLocation();
            telemetry.event("lobby.position_sample", player.getUniqueId(), Map.of(
                    "world", loc.getWorld().getName(), "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ(),
                    "zone", String.valueOf(lobbyPlayer.currentZoneId()), "poi", String.valueOf(lobbyPlayer.currentPoiId())));
        }
    }

    private <T extends Service> T start(T service) throws Exception {
        service.start();
        started.push(service);
        return service;
    }

    private void register(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    public TasticCoreApi coreApi() {
        return coreApi;
    }

    public CookieModule cookie() {
        return cookie;
    }

    public PassModule pass() {
        return pass;
    }
}
