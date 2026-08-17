package de.tasticgames.lobby.integration.mob;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Optional;

/**
 * Custom mob backend (MythicMobs). The main cookie can be a MythicMobs mob type that carries the
 * ModelEngine model configured by the builders.
 */
public interface MobProvider extends Integration {

    boolean hasMobType(String type);

    /** Spawns the mob (level 1) and returns its Bukkit entity; empty on failure. Main thread only. */
    Optional<Entity> spawn(String type, Location location);

    /** Whether the entity is a MythicMobs mob (any type). */
    boolean isCustomMob(Entity entity);

    /** Mob type of the entity when it is a MythicMobs mob. */
    Optional<String> mobType(Entity entity);

    /** Removes the mob (despawn without drops/death effects where the API allows). */
    void remove(Entity entity);

    /** Casts a MythicMobs skill with the entity as caster (e.g. a hit animation); false when unavailable. */
    boolean castSkill(Entity entity, String skill);
}
