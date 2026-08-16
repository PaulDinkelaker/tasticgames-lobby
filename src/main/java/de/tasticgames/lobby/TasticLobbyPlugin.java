package de.tasticgames.lobby;

import de.tasticgames.TasticCorePlugin;
import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.dialog.LobbyDialogService;
import de.tasticgames.lobby.listener.LobbyLanguageChangedListener;
import de.tasticgames.lobby.listener.LobbyPlayerSettingChangedListener;
import de.tasticgames.lobby.player.LobbyPlayerInitializationService;
import de.tasticgames.lobby.player.LobbyPlayerReadyListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class TasticLobbyPlugin
        extends JavaPlugin {

    private TasticCoreApi coreApi;
    private LobbyDialogService dialogService;
    private LobbyPlayerInitializationService playerInitializationService;
    private LobbyPlayerReadyListener playerReadyListener;
    private LobbyLanguageChangedListener languageChangedListener;
    private LobbyPlayerSettingChangedListener playerSettingChangedListener;

    @Override
    public void onEnable() {
        getLogger().info(
                "Starting TasticLobby..."
        );

        try {
            coreApi =
                    TasticCorePlugin
                            .instance()
                            .api();

            getLogger().info(
                    "Connected to TasticCore API."
            );

            getLogger().info(
                    "Registered settings: "
                            + coreApi
                            .settingRegistry()
                            .size()
            );

            getLogger().info(
                    "Loaded players: "
                            + coreApi
                            .playerManager()
                            .onlinePlayers()
                            .size()
            );
        } catch (Exception exception) {
            getLogger().severe(
                    "Failed to initialize TasticLobby: "
                            + safeMessage(
                            exception
                    )
            );

            getServer()
                    .getPluginManager()
                    .disablePlugin(
                            this
                    );

            return;
        }

        try {
            dialogService =
                    new LobbyDialogService(
                            this
                    );

            playerInitializationService =
                    new LobbyPlayerInitializationService(
                            this
                    );

            playerReadyListener =
                    new LobbyPlayerReadyListener(
                            this,
                            dialogService,
                            playerInitializationService
                    );

            languageChangedListener =
                    new LobbyLanguageChangedListener(
                            this,
                            playerInitializationService
                    );

            playerSettingChangedListener =
                    new LobbyPlayerSettingChangedListener(
                            this
                    );

            getServer()
                    .getPluginManager()
                    .registerEvents(
                            playerReadyListener,
                            this
                    );

            getLogger().info(
                    "Registered lobby player ready listener."
            );

            getServer()
                    .getPluginManager()
                    .registerEvents(
                            languageChangedListener,
                            this
                    );

            getLogger().info(
                    "Registered lobby language changed listener."
            );

            getServer()
                    .getPluginManager()
                    .registerEvents(
                            playerSettingChangedListener,
                            this
                    );

            getLogger().info(
                    "Registered lobby player setting changed listener."
            );

            getLogger().info(
                    "TasticLobby started successfully."
            );
        } catch (Exception exception) {
            getLogger().severe(
                    "Failed to register TasticLobby services and listeners: "
                            + safeMessage(
                            exception
                    )
            );

            getServer()
                    .getPluginManager()
                    .disablePlugin(
                            this
                    );
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(
                this
        );

        playerSettingChangedListener =
                null;

        languageChangedListener =
                null;

        playerReadyListener =
                null;

        playerInitializationService =
                null;

        dialogService =
                null;

        coreApi =
                null;

        getLogger().info(
                "TasticLobby stopped."
        );
    }

    public TasticCoreApi coreApi() {
        TasticCoreApi current =
                coreApi;

        if (current == null) {
            throw new IllegalStateException(
                    "TasticLobby is not initialized."
            );
        }

        return current;
    }

    private String safeMessage(
            Throwable throwable
    ) {
        String message =
                throwable.getMessage();

        if (message == null
                || message.isBlank()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}
