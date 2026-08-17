package de.tasticgames.lobby.integration.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.events.BaseEntityInteractEvent;
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
 */
public final class ModelEngineModelProvider implements ModelProvider, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, Set<String>> attached = new ConcurrentHashMap<>();
    private final List<InteractHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean available;
    private volatile boolean warned;

    public ModelEngineModelProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            ModelEngineAPI.getAPI();
            Bukkit.getPluginManager().registerEvents(this, plugin);
            available = true;
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
        HandlerList.unregisterAll(this);
        available = false;
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
    public boolean attach(Entity base, String modelId) {
        if (!available || base == null || modelId == null || modelId.isBlank()) return false;
        try {
            ModeledEntity modeled = ModelEngineAPI.getOrCreateModeledEntity(base);
            if (modeled == null) {
                return false;
            }
            Optional<ActiveModel> existing = modeled.getModel(modelId);
            if (existing.isEmpty()) {
                ActiveModel model = ModelEngineAPI.createActiveModel(modelId);
                if (model == null) {
                    return false;
                }
                modeled.addModel(model, true);
            }
            modeled.setBaseEntityVisible(false);
            attached.computeIfAbsent(base.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(modelId);
            return true;
        } catch (Throwable t) {
            warnOnce("attach " + modelId, t);
            return false;
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

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("ModelEngine " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
