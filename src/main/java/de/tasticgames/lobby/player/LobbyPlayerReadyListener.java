package de.tasticgames.lobby.player;

import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.lobby.dialog.LobbyDialogService;
import de.tasticgames.onboarding.PlayerOnboarding;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.player.event.TasticPlayerReadyEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;
import java.util.UUID;

public final class LobbyPlayerReadyListener
        implements Listener {

    private final TasticLobbyPlugin plugin;
    private final LobbyDialogService dialogService;
    private final LobbyPlayerInitializationService initializationService;

    public LobbyPlayerReadyListener(
            TasticLobbyPlugin plugin,
            LobbyDialogService dialogService,
            LobbyPlayerInitializationService initializationService
    ) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );

        this.dialogService = Objects.requireNonNull(
                dialogService,
                "dialogService"
        );

        this.initializationService = Objects.requireNonNull(
                initializationService,
                "initializationService"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTasticPlayerReady(
            TasticPlayerReadyEvent event
    ) {
        TasticPlayer tasticPlayer =
                event.tasticPlayer();

        UUID minecraftUuid =
                tasticPlayer.minecraftUuid();

        Player player =
                Bukkit.getPlayer(
                        minecraftUuid
                );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        if (!tasticPlayer.runtime()
                .serverName()
                .equalsIgnoreCase(
                        "lobby"
                )) {
            return;
        }

        plugin.getLogger().info(
                "Lobby runtime ready for "
                        + tasticPlayer.username()
                        + " ["
                        + minecraftUuid
                        + "]"
        );

        PlayerOnboarding onboarding =
                plugin.coreApi()
                        .playerOnboardingService()
                        .require(
                                minecraftUuid
                        );

        plugin.getLogger().info(
                "Onboarding state for "
                        + player.getName()
                        + ": step="
                        + onboarding.currentStep()
                        + ", languageSelected="
                        + onboarding.languageSelected()
                        + ", completed="
                        + onboarding.completed()
        );

        if (!onboarding.languageSelected()) {
            dialogService.openLanguageSelectionDialog(
                    player
            );

            return;
        }

        initializationService.initialize(
                player,
                tasticPlayer
        );
    }
}
