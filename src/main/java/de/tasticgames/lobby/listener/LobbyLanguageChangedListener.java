package de.tasticgames.lobby.listener;

import de.tasticgames.localization.TranslationKey;
import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.lobby.player.LobbyPlayerInitializationService;
import de.tasticgames.player.event.TasticPlayerLanguageChangedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Objects;

public final class LobbyLanguageChangedListener
        implements Listener {

    private static final TranslationKey LANGUAGE_CHANGED =
            TranslationKey.of(
                    "player.language.changed"
            );

    private final TasticLobbyPlugin plugin;
    private final LobbyPlayerInitializationService initializationService;

    public LobbyLanguageChangedListener(
            TasticLobbyPlugin plugin,
            LobbyPlayerInitializationService initializationService
    ) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );

        this.initializationService =
                Objects.requireNonNull(
                        initializationService,
                        "initializationService"
                );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLanguageChanged(
            TasticPlayerLanguageChangedEvent event
    ) {
        Player player =
                Bukkit.getPlayer(
                        event.tasticPlayer()
                                .minecraftUuid()
                );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        player.sendMessage(
                plugin.coreApi()
                        .localizationService()
                        .translate(
                                event.newLanguage(),
                                LANGUAGE_CHANGED,
                                Map.of(
                                        "language",
                                        event.newLanguage()
                                                .displayName()
                                )
                        )
        );

        initializationService.initialize(
                player,
                event.tasticPlayer()
        );

        plugin.getLogger().info(
                "Updated language of "
                        + event.tasticPlayer()
                        .username()
                        + " to "
                        + event.newLanguage()
                        .code()
        );
    }
}
