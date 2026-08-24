package de.tasticgames.lobby.world;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Central spawn: configured location, async (chunk-safe) teleports, duplicate-teleport guard,
 * admin {@code /tasticlobby setspawn} persistence.
 */
public final class LobbySpawnService implements Service {

    private final LobbyConfigurationService configurationService;
    private final LobbyPlayerService players;
    private final Logger logger;
    private volatile LobbyConfiguration.SpawnPoint spawn;

    public LobbySpawnService(LobbyConfigurationService configurationService, LobbyPlayerService players, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.players = Objects.requireNonNull(players);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-spawn-service";
    }

    @Override
    public void start() {
        spawn = configurationService.configuration().world().spawn();
        if (Bukkit.getWorld(spawn.world()) == null) {
            logger.warning("Lobby world '" + spawn.world() + "' is not loaded yet – spawn resolves lazily (Multiverse may load it later).");
        }
    }

    @Override
    public void stop() {
    }

    public Optional<Location> spawnLocation() {
        World world = Bukkit.getWorld(spawn.world());
        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
            if (world == null) {
                return Optional.empty();
            }
        }
        return Optional.of(spawn.toLocation(world));
    }

    public Location requireSpawn() {
        return spawnLocation().orElseThrow(() -> new IllegalStateException("No lobby world available for spawn."));
    }

    public boolean isLobbyWorld(World world) {
        return world != null && world.getName().equals(configurationService.configuration().world().name());
    }

    /** Teleports the player to spawn (async chunk load) with a duplicate-teleport guard. */
    public CompletableFuture<Boolean> teleportToSpawn(Player player) {
        Optional<Location> target = spawnLocation();
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return teleport(player, target.get());
    }

    public CompletableFuture<Boolean> teleport(Player player, Location target) {
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.teleporting()) {
            return CompletableFuture.completedFuture(false);
        }

        lobbyPlayer.teleporting(true);
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, 0, 0));

        return player.teleportAsync(
                        target,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN
                )
                .whenComplete((ok, throwable) -> lobbyPlayer.teleporting(false))
                .exceptionally(throwable -> {
                    logger.warning("Teleport of " + player.getName() + " failed: " + throwable.getMessage());
                    return false;
                });
    }

    /** Persists a new spawn into config/lobby.yml. */
    public void setSpawn(Location location) throws IOException {
        Objects.requireNonNull(location.getWorld(), "world");

        File file = new File(configurationService.configDirectory(), "lobby.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        yaml.set("world.spawn.world", location.getWorld().getName());
        yaml.set("world.spawn.x", location.getX());
        yaml.set("world.spawn.y", location.getY());
        yaml.set("world.spawn.z", location.getZ());
        yaml.set("world.spawn.yaw", (double) location.getYaw());
        yaml.set("world.spawn.pitch", (double) location.getPitch());

        yaml.save(file);

        spawn = new LobbyConfiguration.SpawnPoint(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );

        logger.info("Lobby spawn set to " + spawn);
    }
}
