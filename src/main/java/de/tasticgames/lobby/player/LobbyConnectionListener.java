package de.tasticgames.lobby.player;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.dialog.LanguageDialogService;
import de.tasticgames.lobby.dialog.WelcomeDialogService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.onboarding.PlayerOnboarding;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.player.event.TasticPlayerLanguageChangedEvent;
import de.tasticgames.player.event.TasticPlayerReadyEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Join/ready/quit lifecycle: spawn location, join message suppression, onboarding (language),
 * welcome, initialization, cleanup hooks.
 */
public final class LobbyConnectionListener implements Listener {

    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyPlayerService players;
    private final LobbySpawnService spawn;
    private final LobbyPlayerInitializationService initialization;
    private final LanguageDialogService languageDialog;
    private final WelcomeDialogService welcomeDialog;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final List<Consumer<Player>> quitHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public LobbyConnectionListener(TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyPlayerService players,
                                   LobbySpawnService spawn, LobbyPlayerInitializationService initialization,
                                   LanguageDialogService languageDialog, WelcomeDialogService welcomeDialog,
                                   LobbyTelemetryService telemetry, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.players = Objects.requireNonNull(players);
        this.spawn = Objects.requireNonNull(spawn);
        this.initialization = Objects.requireNonNull(initialization);
        this.languageDialog = Objects.requireNonNull(languageDialog);
        this.welcomeDialog = Objects.requireNonNull(welcomeDialog);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    public void addQuitHook(Consumer<Player> hook) {
        quitHooks.add(hook);
    }

    @EventHandler
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        spawn.spawnLocation().ifPresent(event::setSpawnLocation);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (configurationService.configuration().world().suppressJoinQuitMessages()) {
            event.joinMessage(null);
        }
        players.getOrCreate(event.getPlayer());
        telemetry.event("lobby.join", event.getPlayer().getUniqueId(), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(TasticPlayerReadyEvent event) {
        TasticPlayer tasticPlayer = event.tasticPlayer();
        Player player = Bukkit.getPlayer(tasticPlayer.minecraftUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerOnboarding onboarding = coreApi.playerOnboardingService().find(tasticPlayer.minecraftUuid()).orElse(null);
        if (onboarding != null && !onboarding.languageSelected()) {
            // keep the player in a controlled state while the language dialog is open
            LobbyPlayer lobbyPlayer = players.getOrCreate(player);
            initialization.applyLobbyState(player, lobbyPlayer, true);
            languageDialog.open(player);
            return;
        }
        initialization.initialize(player, tasticPlayer);
        if (onboarding != null && !onboarding.completed()) {
            welcomeDialog.openIfNeeded(player, tasticPlayer);
        }
    }

    /** Core could not load the account (API down / wrong key): neutral lobby state + feedback instead of a raw player. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoadFailed(de.tasticgames.player.event.TasticPlayerLoadFailedEvent event) {
        Player player = event.player() != null ? event.player() : Bukkit.getPlayer(event.minecraftUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        initialization.applyLobbyState(player, lobbyPlayer, true);
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<red>Your TasticGames account could not be loaded right now. Please rejoin in a moment.</red>"));
        logger.warning("TasticCore could not load " + player.getName() + ": " + (event.cause() == null ? "unknown" : event.cause().getMessage()));
        telemetry.event("lobby.load_failed", player.getUniqueId(), Map.of("cause", event.cause() == null ? "unknown" : String.valueOf(event.cause().getMessage())));
    }

    /** Continuation used by the language dialog when the selection did not change the language (no Core event). */
    public void continueAfterLanguage(Player player, TasticPlayer tasticPlayer) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean onboarding = coreApi.playerOnboardingService().find(player.getUniqueId()).map(o -> !o.completed()).orElse(false);
        initialization.initialize(player, tasticPlayer);
        if (onboarding) {
            welcomeDialog.openIfNeeded(player, tasticPlayer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLanguageChanged(TasticPlayerLanguageChangedEvent event) {
        Player player = Bukkit.getPlayer(event.tasticPlayer().minecraftUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean wasOnboarding = coreApi.playerOnboardingService().find(player.getUniqueId()).map(o -> !o.completed()).orElse(false);
        initialization.initialize(player, event.tasticPlayer());
        if (wasOnboarding) {
            welcomeDialog.openIfNeeded(player, event.tasticPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (configurationService.configuration().world().suppressJoinQuitMessages()) {
            event.quitMessage((Component) null);
        }
        Player player = event.getPlayer();
        for (Consumer<Player> hook : quitHooks) {
            try {
                hook.accept(player);
            } catch (RuntimeException e) {
                logger.warning("Quit hook failed for " + player.getName() + ": " + e.getMessage());
            }
        }
        players.remove(player.getUniqueId());
        telemetry.event("lobby.quit", player.getUniqueId(), Map.of());
    }
}
