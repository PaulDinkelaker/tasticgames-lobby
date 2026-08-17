package de.tasticgames.lobby.integration.model;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Custom entity model backend (ModelEngine). Used for the main cookie and NPC models.
 * <p>
 * ModelEngine registers its blueprints <em>after</em> the plugins enabled (model import, asset
 * generation, pack zip). Bindings therefore have to wait for {@link #modelsReady()} /
 * {@link #onModelsReady(Runnable)}; attaching earlier fails with "blueprint missing".
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

    /** Whether the model plugin finished registering its blueprints (ModelEngine: registration phase FINISHED). */
    boolean modelsReady();

    /**
     * Registers a callback that runs on the main thread every time the model registration finished
     * (initial import and every reload). Callers check {@link #modelsReady()} for the initial state.
     */
    void onModelsReady(Runnable callback);

    /** Whether the model id is known to the model plugin (blueprint exists). */
    boolean hasModel(String modelId);

    /**
     * Attaches the model to the base entity (idempotent for the same model).
     *
     * @return empty on success, otherwise the reason ("blueprint 'x' missing", "models not registered yet", exception)
     */
    Optional<String> attachModel(Entity base, String modelId);

    /** Convenience for {@link #attachModel(Entity, String)}: true on success. */
    default boolean attach(Entity base, String modelId) {
        return attachModel(base, modelId).isEmpty();
    }

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
