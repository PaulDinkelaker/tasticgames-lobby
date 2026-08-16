package de.tasticgames.lobby.player;

import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.player.TasticPlayer;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class LobbyPlayerInitializationService {

    private final TasticLobbyPlugin plugin;

    public LobbyPlayerInitializationService(
            TasticLobbyPlugin plugin
    ) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );
    }

    public void initialize(
            Player player,
            TasticPlayer tasticPlayer
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Objects.requireNonNull(
                tasticPlayer,
                "tasticPlayer"
        );

        if (!player.isOnline()) {
            return;
        }

        if (!player.getUniqueId().equals(
                tasticPlayer.minecraftUuid()
        )) {
            throw new IllegalArgumentException(
                    "Player UUID mismatch."
            );
        }

        if (!tasticPlayer.ready()) {
            throw new IllegalStateException(
                    "TasticPlayer is not ready."
            );
        }

        if (!tasticPlayer.runtime()
                .serverName()
                .equalsIgnoreCase(
                        "lobby"
                )) {
            return;
        }

        /*
         * TODO:
         *
         * - Scoreboard öffnen
         * - UltimateUI HUD laden
         * - Lobby Items geben
         * - Cookie Clicker initialisieren
         * - Musiksystem starten
         * - Cosmetics anwenden
         * - Gateway Status laden
         * - NPC Status synchronisieren
         */

        plugin.getLogger().info(
                "Initialized lobby player "
                        + tasticPlayer.username()
                        + " ["
                        + tasticPlayer.minecraftUuid()
                        + "]"
        );
    }
}
