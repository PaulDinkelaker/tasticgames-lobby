package de.tasticgames.lobby.movement;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.lobby.world.WorldEnvironmentService;
import de.tasticgames.settings.CoreSettings;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Slime block launchpads (velocity, directional pads, fall protection), gold block spawn
 * teleporters (regions or everywhere) and optional double jump.
 */
public final class MovementListener implements Listener {

    private final LobbyConfigurationService configurationService;
    private final WorldEnvironmentService environment;
    private final LobbySpawnService spawn;
    private final LobbyPlayerService players;
    private final TasticCoreApi coreApi;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;

    public MovementListener(LobbyConfigurationService configurationService, WorldEnvironmentService environment, LobbySpawnService spawn,
                            LobbyPlayerService players, TasticCoreApi coreApi, LobbySounds sounds, LobbyTelemetryService telemetry) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.environment = Objects.requireNonNull(environment);
        this.spawn = Objects.requireNonNull(spawn);
        this.players = Objects.requireNonNull(players);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        if (!environment.isManaged(player.getWorld())) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.buildMode() || lobbyPlayer.teleporting()) {
            return;
        }
        LobbyConfiguration.Movement config = configurationService.configuration().movement();
        Location to = event.getTo();
        Material below = to.clone().subtract(0, 1, 0).getBlock().getType();
        Material at = to.getBlock().getType();

        if (config.launchpadsEnabled() && (below == config.launchpadMaterial() || at == config.launchpadMaterial())
                && setting(player, LobbySettings.LAUNCHPADS_ENABLED)) {
            launch(player, lobbyPlayer, to, config);
            return;
        }
        if (config.teleportPadsEnabled() && below == config.teleportPadMaterial() && setting(player, LobbySettings.TELEPORT_PADS_ENABLED)) {
            boolean inRegion = config.teleportPadsEverywhere() || config.teleportPadRegions().stream().anyMatch(r -> r.contains(to));
            if (inRegion) {
                teleport(player, lobbyPlayer, config);
            }
        }
    }

    private void launch(Player player, LobbyPlayer lobbyPlayer, Location to, LobbyConfiguration.Movement config) {
        if (!lobbyPlayer.tryLaunch(config.launchCooldownMillis())) {
            return;
        }
        Vector velocity = null;
        for (LobbyConfiguration.LaunchpadDefinition pad : config.directionalLaunchpads()) {
            if (pad.contains(to)) {
                velocity = new Vector(pad.velocityX(), pad.velocityY(), pad.velocityZ());
                break;
            }
        }
        if (velocity == null) {
            Vector direction = player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() > 0) {
                direction.normalize().multiply(config.launchForwardVelocity());
            }
            velocity = direction.setY(config.launchVerticalVelocity());
        }
        player.setVelocity(velocity);
        lobbyPlayer.launchProtectionUntil(Instant.now().plus(Duration.ofSeconds(8)));
        sounds.play(player, config.launchSound(), 1.0f, 1.2f);
        if (!reducedEffects(player)) {
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
        }
        telemetry.event("lobby.launchpad_use", player.getUniqueId(), Map.of("world", player.getWorld().getName()));
    }

    private void teleport(Player player, LobbyPlayer lobbyPlayer, LobbyConfiguration.Movement config) {
        if (!lobbyPlayer.tryTeleportPad(config.teleportCooldownMillis())) {
            return;
        }
        sounds.play(player, config.teleportSound(), 1.0f, 1.0f);
        if (!reducedEffects(player)) {
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
        }
        spawn.teleportToSpawn(player);
        telemetry.event("lobby.spawn_pad_use", player.getUniqueId(), Map.of("world", player.getWorld().getName()));
    }

    /** Fall protection after a launch (also when the launch left a managed world). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        if (lobbyPlayer != null && lobbyPlayer.launchProtected()) {
            event.setCancelled(true);
            lobbyPlayer.launchProtectionUntil(Instant.EPOCH);
        }
    }

    /** Double jump: toggling flight in a managed world (survival/adventure) launches instead. */
    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        LobbyConfiguration.Movement config = configurationService.configuration().movement();
        if (!config.doubleJumpEnabled() || !environment.isManaged(player.getWorld())) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.buildMode() || !setting(player, LobbySettings.DOUBLE_JUMP_ENABLED)) {
            return;
        }
        event.setCancelled(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        if (!lobbyPlayer.tryDoubleJump(config.doubleJumpCooldownMillis())) {
            return;
        }
        Vector direction = player.getLocation().getDirection().multiply(0.6).setY(config.doubleJumpVelocity());
        player.setVelocity(direction);
        lobbyPlayer.launchProtectionUntil(Instant.now().plus(Duration.ofSeconds(5)));
        sounds.play(player, "minecraft:entity.bat.takeoff", 0.8f, 1.4f);
    }

    /** Re-arms the double jump when the player touches ground. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGround(PlayerMoveEvent event) {
        LobbyConfiguration.Movement config = configurationService.configuration().movement();
        if (!config.doubleJumpEnabled() || !event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        if (!environment.isManaged(player.getWorld()) || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.buildMode() || !setting(player, LobbySettings.DOUBLE_JUMP_ENABLED)) {
            return;
        }
        if (((org.bukkit.entity.LivingEntity) player).isOnGround() && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
    }

    private boolean setting(Player player, de.tasticgames.settings.SettingKey<Boolean> key) {
        return coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(key)).orElse(true);
    }

    private boolean reducedEffects(Player player) {
        return coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(CoreSettings.REDUCED_EFFECTS)).orElse(false);
    }
}
