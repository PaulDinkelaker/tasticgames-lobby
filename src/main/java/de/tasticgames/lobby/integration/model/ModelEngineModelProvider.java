package de.tasticgames.lobby.integration.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.events.BaseEntityInteractEvent;
import com.ticxo.modelengine.api.events.ModelRegistrationEvent;
import com.ticxo.modelengine.api.generator.ModelGenerator;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import de.tasticgames.lobby.integration.Integration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ModelEngine (R4) backend: attaches blueprints to base entities and routes model hitbox clicks
 * ({@link BaseEntityInteractEvent}) to the lobby.
 * <p>
 * ModelEngine imports and registers its models after the server started (and on {@code /meg reload});
 * {@link ModelRegistrationEvent} with phase {@code FINISHED} marks the moment blueprints exist. Until then
 * {@link #modelsReady()} is false and {@link #attachModel} reports "models not registered yet" – callers
 * bind their models from {@link #onModelsReady(Runnable)}.
 */
public final class ModelEngineModelProvider implements ModelProvider, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, Set<String>> attached = new ConcurrentHashMap<>();
    private final List<InteractHandler> handlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> readyCallbacks = new CopyOnWriteArrayList<>();
    private final Set<String> warnedOperations = ConcurrentHashMap.newKeySet();
    private volatile boolean available;
    private volatile boolean modelsReady;

    public ModelEngineModelProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            ModelEngineAPI api = ModelEngineAPI.getAPI();
            Bukkit.getPluginManager().registerEvents(this, plugin);
            available = true;
            boolean initialized = false;
            try {
                initialized = api.getModelGenerator() != null && api.getModelGenerator().isInitialized();
            } catch (Throwable ignored) {
                // older API without generator access: wait for the registration event
            }
            modelsReady = initialized;
            if (initialized) {
                logger.info("ModelEngine detected – " + blueprintCount() + " models registered.");
            } else {
                logger.info("ModelEngine detected – waiting for model registration (ModelRegistrationEvent FINISHED) before binding models.");
            }
        } catch (Throwable t) {
            available = false;
            logger.warning("ModelEngine hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – models disabled.");
        }
    }

    public void unhook() {
        for (UUID id : List.copyOf(attached.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                detach(entity);
            }
        }
        attached.clear();
        readyCallbacks.clear();
        HandlerList.unregisterAll(this);
        available = false;
        modelsReady = false;
    }

    @Override
    public String pluginName() {
        return "ModelEngine";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String status() {
        String base = ModelProvider.super.status();
        if (!available) {
            return base;
        }
        return base + (modelsReady ? ", " + blueprintCount() + " models registered" : ", waiting for model registration");
    }

    @Override
    public boolean modelsReady() {
        return available && modelsReady;
    }

    @Override
    public void onModelsReady(Runnable callback) {
        readyCallbacks.add(Objects.requireNonNull(callback));
    }

    @Override
    public boolean hasModel(String modelId) {
        if (!available || modelId == null || modelId.isBlank()) return false;
        try {
            return ModelEngineAPI.getBlueprint(modelId) != null;
        } catch (Throwable t) {
            warnOnce("blueprint lookup", t);
            return false;
        }
    }

    @Override
    public Optional<String> attachModel(Entity base, String modelId) {
        if (!available) return Optional.of("ModelEngine unavailable");
        if (base == null || !base.isValid()) return Optional.of("base entity missing");
        if (modelId == null || modelId.isBlank()) return Optional.of("no model id");
        if (!modelsReady) return Optional.of("models not registered yet");
        try {
            ModelBlueprint blueprint = ModelEngineAPI.getBlueprint(modelId);
            if (blueprint == null) {
                return Optional.of("blueprint '" + modelId + "' missing");
            }
            ModeledEntity modeled = ModelEngineAPI.getOrCreateModeledEntity(base);
            if (modeled == null) {
                return Optional.of("modeled entity could not be created");
            }
            Optional<ActiveModel> existing = modeled.getModel(modelId);
            if (existing.isEmpty()) {
                ActiveModel model = ModelEngineAPI.createActiveModel(blueprint);
                if (model == null) {
                    return Optional.of("active model could not be created");
                }
                modeled.addModel(model, true);
            }
            modeled.setBaseEntityVisible(false);
            attached.computeIfAbsent(base.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(modelId);
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.of(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void detach(Entity base) {
        if (base == null) return;
        Set<String> ours = attached.remove(base.getUniqueId());
        if (!available || ours == null || ours.isEmpty()) return;
        try {
            ModeledEntity modeled = ModelEngineAPI.getModeledEntity(base.getUniqueId());
            if (modeled == null) return;
            for (String id : ours) {
                modeled.removeModel(id);
            }
            if (modeled.getModels().isEmpty()) {
                ModelEngineAPI.removeModeledEntity(base.getUniqueId());
            }
        } catch (Throwable t) {
            warnOnce("detach", t);
        }
    }

    @Override
    public boolean isModeled(Entity entity) {
        if (!available || entity == null) return false;
        try {
            return ModelEngineAPI.isModeledEntity(entity.getUniqueId());
        } catch (Throwable t) {
            return attached.containsKey(entity.getUniqueId());
        }
    }

    @Override
    public boolean hideBase(Entity entity) {
        if (!available || entity == null) return false;
        try {
            ModeledEntity modeled = ModelEngineAPI.getModeledEntity(entity.getUniqueId());
            if (modeled == null) {
                return false;
            }
            modeled.setBaseEntityVisible(false);
            return true;
        } catch (Throwable t) {
            warnOnce("hide base", t);
            return false;
        }
    }

    @Override
    public void onInteract(InteractHandler handler) {
        handlers.add(Objects.requireNonNull(handler));
    }

    @EventHandler
    public void onModelRegistration(ModelRegistrationEvent event) {
        ModelGenerator.Phase phase;
        try {
            phase = event.getPhase();
        } catch (Throwable t) {
            warnOnce("registration event", t);
            return;
        }
        if (phase != ModelGenerator.Phase.FINISHED) {
            return;
        }
        boolean first = !modelsReady;
        modelsReady = true;
        logger.info("ModelEngine model registration finished (" + blueprintCount() + " models)" + (first ? " – binding lobby models." : " – re-binding lobby models."));
        Runnable notify = () -> {
            for (Runnable callback : readyCallbacks) {
                try {
                    callback.run();
                } catch (RuntimeException e) {
                    logger.warning("ModelEngine ready callback failed: " + e.getMessage());
                }
            }
        };
        // next tick on the main thread: ModelEngine finishes its own bookkeeping for this phase first
        Bukkit.getScheduler().runTask(plugin, notify);
    }

    @EventHandler
    public void onBaseEntityInteract(BaseEntityInteractEvent event) {
        UUID base;
        boolean left;
        try {
            base = event.getBaseEntity().getUUID();
            left = event.getAction() == BaseEntityInteractEvent.Action.ATTACK;
        } catch (Throwable t) {
            warnOnce("interact event", t);
            return;
        }
        // BaseEntityInteractEvent is informational (not Cancellable); the underlying damage/interaction is
        // prevented by the lobby's own entity listeners.
        for (InteractHandler handler : handlers) {
            try {
                handler.onInteract(event.getPlayer(), base, left);
            } catch (RuntimeException e) {
                logger.warning("Model interact handler failed: " + e.getMessage());
            }
        }
    }

    private int blueprintCount() {
        try {
            return ModelEngineAPI.getAPI().getModelRegistry().getOrderedId().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** One warning per operation type (not one for everything) so distinct failures stay visible. */
    private void warnOnce(String operation, Throwable t) {
        if (warnedOperations.add(operation)) {
            logger.warning("ModelEngine " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ").");
        }
    }
}
