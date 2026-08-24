package de.tasticgames.lobby.world;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Keeps the lobby world stable: fixed time, clear weather, no natural mob spawning.
 * Only touches the configured lobby world (and the cookie world when it exists).
 */
public final class WorldEnvironmentService implements Service {

    private final Plugin plugin;
    private final LobbyConfigurationService configurationService;
    private final java.util.function.Supplier<java.util.Set<String>> managedWorlds;
    private final Logger logger;
    private BukkitTask task;

    public WorldEnvironmentService(
            Plugin plugin,
            LobbyConfigurationService configurationService,
            java.util.function.Supplier<java.util.Set<String>> managedWorlds,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.managedWorlds = Objects.requireNonNull(managedWorlds);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "world-environment-service";
    }

    @Override
    public void start() {
        apply();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::apply, 20L * 30, 20L * 30);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isManaged(World world) {
        return world != null && managedWorlds.get().contains(world.getName());
    }

    public void apply() {
        LobbyConfiguration.World config = configurationService.configuration().world();

        for (String name : managedWorlds.get()) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                continue;
            }

            if (config.fixedTime()) {
                world.setGameRule(GameRules.ADVANCE_TIME, false);

                if (Math.abs(world.getTime() - config.fixedTimeTicks()) > 100) {
                    world.setTime(config.fixedTimeTicks());
                }
            }

            if (config.clearWeather()) {
                world.setGameRule(GameRules.ADVANCE_WEATHER, false);

                if (world.hasStorm() || world.isThundering()) {
                    world.setStorm(false);
                    world.setThundering(false);
                }
            }

            if (config.disableMobSpawning()) {
                world.setGameRule(GameRules.SPAWN_MOBS, false);
                world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
                world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
                world.setGameRule(GameRules.SPAWN_PATROLS, false);
            }

            if (config.disableLocatorBar()) {
                // 1.21.6+ locator bar: off everywhere, it clutters the HUD and reveals every player's direction.
                world.setGameRule(GameRules.LOCATOR_BAR, false);
            }

            world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            world.setGameRule(GameRules.MOB_GRIEFING, false);
            world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        }
    }
}
