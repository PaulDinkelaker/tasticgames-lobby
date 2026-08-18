package de.tasticgames.lobby.pass;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.cookie.CookieProgressListener;
import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.pass.PassSnapshot;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * TasticPass module facade: wires the state cache, the Cookie Clicker XP hooks, the dialogs, the
 * event feedback and the commands, and exposes the integration points the lobby uses (placeholders,
 * profile line, diagnostics, the dialog opener for the pass service NPC). All pass traffic goes
 * through TasticCore's {@code PlayerPassService}; with the pass API down every entry point degrades
 * to a localized "temporarily unavailable" and the rest of the lobby is unaffected.
 */
public final class PassModule implements Service {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final Logger logger;
    private final AtomicReference<PassConfiguration> configuration = new AtomicReference<>();
    private final List<Service> services = new ArrayList<>();

    private PassStateService state;
    private PassXpService xp;
    private PassDialogService dialogService;
    private PassEventListener listener;
    private PassCommand command;
    private PassAdminCommand adminCommand;

    public PassModule(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyApiService api, LobbyMessages messages,
                      LobbySounds sounds, LobbyTelemetryService telemetry, DialogSupport dialogs, MainThread mainThread, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "pass-module";
    }

    @Override
    public void start() throws Exception {
        configuration.set(PassConfiguration.load(configurationService.raw("pass")));
        state = add(new PassStateService(coreApi.playerPassService(), configuration::get, logger));
        xp = add(new PassXpService(plugin, coreApi.playerPassService(), state, configuration::get, logger));
        dialogService = new PassDialogService(state, configuration::get, messages, dialogs, mainThread, sounds, telemetry, logger);
        listener = new PassEventListener(state, dialogService, messages, sounds, telemetry);
        command = new PassCommand(dialogService, messages);
        adminCommand = new PassAdminCommand(api, state, xp, messages, telemetry, mainThread, plugin.getDataFolder(), this::reload, logger);
        for (Service service : services) {
            service.start();
        }
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void stop() {
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        for (int i = services.size() - 1; i >= 0; i--) {
            try {
                services.get(i).stop();
            } catch (Exception e) {
                logger.warning("Pass service " + services.get(i).id() + " failed to stop: " + LobbyThrowables.rootMessage(e));
            }
        }
        services.clear();
    }

    private <T extends Service> T add(T service) {
        services.add(service);
        return service;
    }

    /** Re-reads config/pass.yml; rates and toggles apply to the next flush/dialog without a restart. */
    public void reload() {
        try {
            configurationService.reload();
            configuration.set(PassConfiguration.load(configurationService.raw("pass")));
            logger.info("config/pass.yml reloaded.");
        } catch (Exception e) {
            logger.warning("Pass configuration reload failed: " + LobbyThrowables.rootMessage(e));
        }
    }

    // ------------------------------------------------------------------ integration points

    public PassConfiguration configuration() {
        return configuration.get();
    }

    public PassStateService state() {
        return state;
    }

    /** Dialog service – also used by the pass service NPC to open the overview. */
    public PassDialogService dialogs() {
        return dialogService;
    }

    /**
     * Cookie Clicker progress consumer; registered on the cookie module by the bootstrap. The
     * service itself drops everything while the pass is disabled, so the wiring survives a reload.
     */
    public CookieProgressListener progressListener() {
        return xp;
    }

    public PassCommand command() {
        return command;
    }

    public PassAdminCommand adminCommand() {
        return adminCommand;
    }

    /** Opens the pass overview (service NPC, /pass); shows the unavailable notice when it is off. */
    public void openOverview(Player player) {
        dialogService.openOverview(player);
    }

    /** Post-init hook: loads the pass state so XP reports and the HUD have a state to work with. */
    public void preload(Player player) {
        state.preload(player);
    }

    /** Quit hook: flushes what the player accumulated; core unloads its own state. */
    public void onQuit(Player player) {
        xp.flush(player.getUniqueId());
        state.forget(player.getUniqueId());
    }

    /**
     * Placeholder values (%tastic_pass_*%): level, xp, xp_next, progress, premium, season,
     * quests_done, claimable. Empty string when the player has no pass state.
     */
    public String placeholder(Player player, String key) {
        PassSnapshot snapshot = state.state(player.getUniqueId()).orElse(null);
        if (snapshot == null) {
            return "";
        }
        SupportedLanguage lang = messages.languageOf(player);
        return switch (key) {
            case "level" -> String.valueOf(snapshot.level());
            case "xp" -> String.valueOf(snapshot.xpIntoLevel());
            case "xp_next" -> String.valueOf(snapshot.xpForNextLevel());
            case "progress" -> PassFormat.percent(snapshot.xpIntoLevel(), snapshot.xpForNextLevel()) + "%";
            case "premium" -> String.valueOf(snapshot.premium());
            case "season" -> snapshot.seasonName() == null ? state.season().map(s -> s.displayName()).orElse("") : snapshot.seasonName();
            case "quests_done" -> String.valueOf(PassStateService.questsCompleted(snapshot));
            case "claimable" -> String.valueOf(state.claimableCount(snapshot));
            case "total_xp" -> PassFormat.number(snapshot.totalXp(), lang);
            default -> "";
        };
    }

    /** Pass level for the profile context of the HUD ({@code -} while no state is loaded). */
    public String levelText(Player player) {
        return state.state(player.getUniqueId()).map(snapshot -> String.valueOf(snapshot.level())).orElse("-");
    }

    /**
     * Summary line of the profile dialog: level, progress and premium state. Only the viewer's own
     * profile reports an unavailable pass – for other players the line is simply left out, because
     * their state is only loaded while they are on this server.
     */
    public java.util.Optional<net.kyori.adventure.text.Component> profileLine(Player viewer, java.util.UUID target) {
        if (!state.enabled()) {
            return java.util.Optional.empty();
        }
        PassSnapshot snapshot = state.state(target).orElse(null);
        SupportedLanguage lang = messages.languageOf(viewer);
        if (snapshot == null) {
            return viewer.getUniqueId().equals(target)
                    ? java.util.Optional.of(messages.get(lang, "lobby.profile.pass_unavailable", Map.of()))
                    : java.util.Optional.empty();
        }
        return java.util.Optional.of(messages.get(lang, "lobby.profile.pass", Map.of(
                "level", snapshot.level(), "max", snapshot.maxLevel(),
                "bar", PassFormat.progressBar(snapshot.xpIntoLevel(), snapshot.xpForNextLevel(), 10),
                "premium", messages.raw(lang, snapshot.premium() ? "pass.premium.yes" : "pass.premium.no").replaceAll("<[^>]+>", "").trim())));
    }

    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        PassConfiguration config = configuration.get();
        if (config == null || !config.enabled()) {
            status.put("TasticPass", "disabled in config/pass.yml");
            return status;
        }
        status.put("TasticPass", state.season()
                .map(season -> season.key() + " '" + season.displayName() + "', " + season.tiers().size() + " tiers, " + season.quests().size() + " quests")
                .orElse("NO ACTIVE SEASON (API unavailable or season not started)"));
        status.put("Pass states", state.loadedCount() + " loaded, " + xp.pendingPlayers() + " with pending progress");
        status.put("Pass XP", config.xp().clicksPerXp() + " clicks/XP, flush every " + config.xp().flushIntervalSeconds() + "s, dialogs "
                + (config.dialogsEnabled() ? "on" : "off") + ", NPC " + (config.npcEnabled() ? "on" : "off"));
        return status;
    }
}
