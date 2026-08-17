package de.tasticgames.lobby.integration.model;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Custom entity model backend (ModelEngine). Used for the main cookie and NPC models.
 */
public interface ModelProvider extends Integration {

    @FunctionalInterface
    interface InteractHandler {
        /**
         * Called on the main thread when a player clicks a modeled base entity.
         *
         * @param baseEntity UUID of the Bukkit base entity carrying the model
         * @param leftClick  true for attack/left click, false for interact/right click
         * @return true to cancel the underlying interaction (damage/interact)
         */
        boolean onInteract(Player player, UUID baseEntity, boolean leftClick);
    }

    /** Whether the model id is known to the model plugin (blueprint exists). */
    boolean hasModel(String modelId);

    /** Attaches the model to the base entity (idempotent for the same model). Returns false on failure. */
    boolean attach(Entity base, String modelId);

    /** Removes our model(s) from the base entity. */
    void detach(Entity base);

    /** Whether the entity currently carries a model managed by the model plugin (ours or foreign). */
    boolean isModeled(Entity entity);

    /**
     * Hides the base entity of a modeled entity (also for models attached by other plugins such as
     * MythicMobs); returns false when the entity carries no model or the plugin is unavailable.
     */
    boolean hideBase(Entity entity);

    void onInteract(InteractHandler handler);
}
