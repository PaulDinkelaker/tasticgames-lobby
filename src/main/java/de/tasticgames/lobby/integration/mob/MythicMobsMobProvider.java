package de.tasticgames.lobby.integration.mob;

import de.tasticgames.lobby.integration.Integration;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * MythicMobs backend: spawns configured mob types (e.g. the main cookie carrying its ModelEngine
 * model) and identifies/removes them.
 */
public final class MythicMobsMobProvider implements MobProvider {

    private final Plugin plugin;
    private final Logger logger;
    private volatile boolean available;
    private volatile boolean warned;

    public MythicMobsMobProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            available = MythicBukkit.inst() != null && MythicBukkit.inst().getMobManager() != null;
        } catch (Throwable t) {
            available = false;
            logger.warning("MythicMobs hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – MythicMobs visuals disabled.");
        }
    }

    public void unhook() {
        available = false;
    }

    @Override
    public String pluginName() {
        return "MythicMobs";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean hasMobType(String type) {
        if (!available || type == null || type.isBlank()) return false;
        try {
            return MythicBukkit.inst().getMobManager().getMythicMob(type).isPresent();
        } catch (Throwable t) {
            warnOnce("mob type lookup", t);
            return false;
        }
    }

    @Override
    public Optional<Entity> spawn(String type, Location location) {
        if (!available || type == null || location == null || location.getWorld() == null) return Optional.empty();
        try {
            Entity entity = MythicBukkit.inst().getAPIHelper().spawnMythicMob(type, location, 1);
            return Optional.ofNullable(entity);
        } catch (Throwable t) {
            warnOnce("spawn " + type, t);
            return Optional.empty();
        }
    }

    @Override
    public boolean isCustomMob(Entity entity) {
        if (!available || entity == null) return false;
        try {
            return MythicBukkit.inst().getMobManager().isActiveMob(entity.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Optional<String> mobType(Entity entity) {
        if (!available || entity == null) return Optional.empty();
        try {
            return MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId()).map(ActiveMob::getMobType);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public void remove(Entity entity) {
        if (entity == null) return;
        try {
            if (available) {
                Optional<ActiveMob> active = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
                if (active.isPresent()) {
                    active.get().remove();
                    return;
                }
            }
        } catch (Throwable t) {
            warnOnce("remove", t);
        }
        entity.remove();
    }

    @Override
    public boolean castSkill(Entity entity, String skill) {
        if (!available || entity == null || skill == null || skill.isBlank()) return false;
        try {
            return MythicBukkit.inst().getAPIHelper().castSkill(entity, skill);
        } catch (Throwable t) {
            warnOnce("cast skill " + skill, t);
            return false;
        }
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("MythicMobs " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
