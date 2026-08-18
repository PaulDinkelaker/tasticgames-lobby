package de.tasticgames.lobby.integration.mob;

import de.tasticgames.lobby.integration.Integration;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicPostReloadedEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * MythicMobs backend: spawns configured mob types (e.g. the main cookie carrying its ModelEngine
 * model) and identifies/removes them. Reload callbacks ({@link MythicPostReloadedEvent}) let the
 * lobby re-check spawned mobs – MythicMobs reloads itself after ItemsAdder loaded its content.
 */
public final class MythicMobsMobProvider implements MobProvider, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final List<Runnable> reloadCallbacks = new CopyOnWriteArrayList<>();
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
            if (available) {
                Bukkit.getPluginManager().registerEvents(this, plugin);
            }
        } catch (Throwable t) {
            available = false;
            logger.warning("MythicMobs hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – MythicMobs visuals disabled.");
        }
    }

    public void unhook() {
        HandlerList.unregisterAll(this);
        reloadCallbacks.clear();
        available = false;
    }

    @Override
    public void onReload(Runnable callback) {
        reloadCallbacks.add(Objects.requireNonNull(callback));
    }

    @EventHandler
    public void onMythicReloaded(MythicPostReloadedEvent event) {
        // next tick: MythicMobs (and ModelEngine's MythicMobs compatibility) finish their own bookkeeping first
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Runnable callback : reloadCallbacks) {
                try {
                    callback.run();
                } catch (RuntimeException e) {
                    logger.warning("MythicMobs reload callback failed: " + e.getMessage());
                }
            }
        });
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
            logger.warning("MythicMobs " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ").");
        }
    }
}
