package de.tasticgames.lobby.listener;

import de.tasticgames.localization.TranslationKey;
import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.player.event.TasticPlayerSettingChangedEvent;
import de.tasticgames.settings.SettingKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Objects;

public final class LobbyPlayerSettingChangedListener
        implements Listener {

    private static final TranslationKey SETTING_CHANGED =
            TranslationKey.of(
                    "player.setting.changed"
            );

    private final TasticLobbyPlugin plugin;

    public LobbyPlayerSettingChangedListener(
            TasticLobbyPlugin plugin
    ) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSettingChanged(
            TasticPlayerSettingChangedEvent event
    ) {
        TasticPlayer tasticPlayer =
                event.tasticPlayer();

        Player player =
                Bukkit.getPlayer(
                        tasticPlayer.minecraftUuid()
                );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        player.sendMessage(
                plugin.coreApi()
                        .localizationService()
                        .translate(
                                tasticPlayer,
                                SETTING_CHANGED,
                                Map.of(
                                        "setting",
                                        event.key().id(),
                                        "value",
                                        String.valueOf(
                                                event.newValue()
                                        )
                                )
                        )
        );

        onSettingUpdated(
                tasticPlayer,
                event.key()
        );
    }

    private void onSettingUpdated(
            TasticPlayer player,
            SettingKey<?> key
    ) {
        switch (key.id()) {

            case "music.enabled" -> {
                // Musiksystem aktualisieren
            }

            case "music.volume" -> {
                // Lautstärke aktualisieren
            }

            case "sounds.enabled" -> {
                // UI Sounds
            }

            case "scoreboard.enabled" -> {
                // UltimateUI Scoreboard
            }

            case "chat.translate" -> {
                // Chat Translation
            }

            default -> {
            }
        }
    }
}
