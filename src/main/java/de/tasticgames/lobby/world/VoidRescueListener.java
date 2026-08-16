package de.tasticgames.lobby.world;

import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;
import java.util.function.Function;

/**
 * Below the configured Y the player is returned to spawn (or the cookie world entry) – no death, no loop.
 */
public final class VoidRescueListener implements Listener {

    private final LobbyConfigurationService configurationService;
    private final WorldEnvironmentService environment;
    private final LobbySpawnService spawn;
    private final LobbyPlayerService players;
    private final LobbyTelemetryService telemetry;
    private final Function<Player, Boolean> cookieRescue;

    public VoidRescueListener(LobbyConfigurationService configurationService, WorldEnvironmentService environment,
                              LobbySpawnService spawn, LobbyPlayerService players, LobbyTelemetryService telemetry,
                              Function<Player, Boolean> cookieRescue) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.environment = Objects.requireNonNull(environment);
        this.spawn = Objects.requireNonNull(spawn);
        this.players = Objects.requireNonNull(players);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.cookieRescue = Objects.requireNonNull(cookieRescue);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (!environment.isManaged(player.getWorld())) {
            return;
        }
        double threshold = configurationService.configuration().world().voidRescueY();
        if (event.getTo().getY() >= threshold) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.teleporting()) {
            return;
        }
        if (lobbyPlayer.inCookieWorld() && cookieRescue.apply(player)) {
            telemetry.event("lobby.void_rescue", player.getUniqueId(), java.util.Map.of("world", player.getWorld().getName()));
            return;
        }
        spawn.teleportToSpawn(player);
        telemetry.event("lobby.void_rescue", player.getUniqueId(), java.util.Map.of("world", player.getWorld().getName()));
    }
}
