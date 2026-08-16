package de.tasticgames.lobby.world;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
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

    public WorldEnvironmentService(Plugin plugin, LobbyConfigurationService configurationService,
                                   java.util.function.Supplier<java.util.Set<String>> managedWorlds, Logger logger) {
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
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                if (Math.abs(world.getTime() - config.fixedTimeTicks()) > 100) {
                    world.setTime(config.fixedTimeTicks());
                }
            }
            if (config.clearWeather()) {
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                if (world.hasStorm() || world.isThundering()) {
                    world.setStorm(false);
                    world.setThundering(false);
                }
            }
            if (config.disableMobSpawning()) {
                world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                world.setGameRule(GameRule.DO_INSOMNIA, false);
                world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
                world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
            }
            world.setGameRule(GameRule.DO_FIRE_TICK, false);
            world.setGameRule(GameRule.MOB_GRIEFING, false);
            world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        }
    }
}
