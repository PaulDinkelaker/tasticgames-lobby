package de.tasticgames.lobby.integration.npc;

import de.tasticgames.lobby.integration.Integration;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Citizens backed NPCs. Our own NPCs live in an in-memory registry (never written to Citizens'
 * saves); NPCs created by admins with {@code /npc create} can be linked via {@code citizens-id}
 * and are left untouched apart from click handling.
 */
public final class CitizensNpcProvider implements NpcProvider, Listener {

    private static final String REGISTRY = "tasticlobby";

    private final Plugin plugin;
    private final Logger logger;
    private final BiFunction<Entity, String, Boolean> modelAttacher;
    private final Map<String, NpcHandle> byId = new ConcurrentHashMap<>();
    private final Map<Integer, String> byCitizensId = new ConcurrentHashMap<>();
    private final Map<UUID, String> byEntity = new ConcurrentHashMap<>();
    private final List<ClickHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile NPCRegistry registry;
    private volatile boolean available;
    private volatile boolean warned;

    public CitizensNpcProvider(Plugin plugin, Logger logger, BiFunction<Entity, String, Boolean> modelAttacher) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
        this.modelAttacher = modelAttacher == null ? (e, m) -> false : modelAttacher;
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            NPCRegistry existing = CitizensAPI.getNamedNPCRegistry(REGISTRY);
            registry = existing != null ? existing : CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
            if (registry == null) {
                registry = CitizensAPI.getNPCRegistry();
            }
            Bukkit.getPluginManager().registerEvents(this, plugin);
            available = registry != null;
        } catch (Throwable t) {
            available = false;
            logger.warning("Citizens hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – using native NPC entities.");
        }
    }

    public void unhook() {
        removeAll();
        HandlerList.unregisterAll(this);
        available = false;
    }

    @Override
    public String pluginName() {
        return "Citizens";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Optional<NpcHandle> spawn(NpcSpec spec) {
        Objects.requireNonNull(spec);
        if (!available || spec.location().getWorld() == null) {
            return Optional.empty();
        }
        NpcHandle previous = byId.remove(spec.id());
        if (previous != null) {
            remove(previous);
        }
        try {
            NPC npc;
            boolean linked = false;
            if (spec.externalId().isPresent()) {
                npc = CitizensAPI.getNPCRegistry().getById(spec.externalId().getAsInt());
                if (npc == null) {
                    logger.warning("Citizens NPC #" + spec.externalId().getAsInt() + " for '" + spec.id() + "' does not exist.");
                    return Optional.empty();
                }
                linked = true;
                if (!npc.isSpawned()) {
                    npc.spawn(npc.getStoredLocation() != null ? npc.getStoredLocation() : spec.location());
                }
            } else {
                String name = LegacyComponentSerializer.legacySection().serialize(spec.displayName());
                EntityType type = spec.entityType() == null ? EntityType.VILLAGER : spec.entityType();
                npc = registry.createNPC(type, name);
                npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
                npc.data().set(NPC.Metadata.SILENT, true);
                npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, true);
                npc.data().set(NPC.Metadata.COLLIDABLE, false);
                npc.setProtected(true);
                try {
                    npc.getOrAddTrait(LookClose.class).lookClose(true);
                } catch (Throwable ignored) {
                    // trait not available in this Citizens build
                }
                if (type == EntityType.PLAYER && !spec.skin().isBlank()) {
                    try {
                        npc.getOrAddTrait(SkinTrait.class).setSkinName(spec.skin());
                    } catch (Throwable ignored) {
                        // skin trait not available
                    }
                }
                spec.location().getWorld().getChunkAt(spec.location()).load();
                if (!npc.spawn(spec.location())) {
                    npc.destroy();
                    return Optional.empty();
                }
            }
            Entity entity = npc.getEntity();
            if (entity != null && !spec.model().isBlank()) {
                try {
                    if (modelAttacher.apply(entity, spec.model())) {
                        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                    }
                } catch (RuntimeException e) {
                    warnOnce("model attach", e);
                }
            }
            NpcHandle handle = new NpcHandle(spec.id(), entity == null ? new UUID(0, 0) : entity.getUniqueId(), npc.getId(), linked);
            byId.put(spec.id(), handle);
            byCitizensId.put(npc.getId(), spec.id());
            if (entity != null) {
                byEntity.put(entity.getUniqueId(), spec.id());
            }
            return Optional.of(handle);
        } catch (Throwable t) {
            warnOnce("spawn", t);
            return Optional.empty();
        }
    }

    @Override
    public void remove(NpcHandle handle) {
        if (handle == null) return;
        byId.remove(handle.id(), handle);
        byCitizensId.remove(handle.externalId());
        byEntity.remove(handle.entityId());
        if (handle.linked()) {
            return;
        }
        try {
            NPC npc = registry == null ? null : registry.getById(handle.externalId());
            if (npc != null) {
                npc.destroy();
            }
        } catch (Throwable t) {
            warnOnce("remove", t);
        }
    }

    @Override
    public void removeAll() {
        for (NpcHandle handle : List.copyOf(byId.values())) {
            remove(handle);
        }
    }

    @Override
    public void onClick(ClickHandler handler) {
        handlers.add(Objects.requireNonNull(handler));
    }

    @Override
    public Optional<String> npcIdOf(Entity entity) {
        if (entity == null) return Optional.empty();
        String direct = byEntity.get(entity.getUniqueId());
        if (direct != null) return Optional.of(direct);
        try {
            NPC npc = registry == null ? null : registry.getNPC(entity);
            if (npc == null) {
                npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            }
            return npc == null ? Optional.empty() : Optional.ofNullable(byCitizensId.get(npc.getId()));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isNpc(Entity entity) {
        if (entity == null) return false;
        try {
            return CitizensAPI.getNPCRegistry().isNPC(entity) || (registry != null && registry.isNPC(entity));
        } catch (Throwable t) {
            return byEntity.containsKey(entity.getUniqueId());
        }
    }

    @Override
    public int count() {
        return byId.size();
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(NPCRightClickEvent event) {
        String id = byCitizensId.get(event.getNPC().getId());
        if (id == null) return;
        event.setCancelled(true);
        dispatch(id, event.getClicker(), false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(NPCLeftClickEvent event) {
        String id = byCitizensId.get(event.getNPC().getId());
        if (id == null) return;
        event.setCancelled(true);
        dispatch(id, event.getClicker(), true);
    }

    private void dispatch(String id, Player player, boolean left) {
        for (ClickHandler handler : handlers) {
            try {
                handler.onClick(id, player, left);
            } catch (RuntimeException e) {
                logger.warning("NPC click handler failed for '" + id + "': " + e.getMessage());
            }
        }
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("Citizens " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
