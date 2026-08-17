package de.tasticgames.lobby.integration.npc;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
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
 * Fallback NPC backend without Citizens: plain entities (AI off, invulnerable, silent, non
 * persistent) tagged with a PDC marker. Stale markers from a previous run are removed on spawn.
 */
public final class NativeNpcProvider implements NpcProvider, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final BiFunction<Entity, String, Boolean> modelAttacher;
    private final NamespacedKey markerKey;
    private final Map<UUID, NpcHandle> byEntity = new ConcurrentHashMap<>();
    private final Map<String, NpcHandle> byId = new ConcurrentHashMap<>();
    private final List<ClickHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean hooked;

    public NativeNpcProvider(Plugin plugin, Logger logger, BiFunction<Entity, String, Boolean> modelAttacher) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
        this.modelAttacher = modelAttacher == null ? (e, m) -> false : modelAttacher;
        this.markerKey = new NamespacedKey(plugin, "native-npc");
    }

    public void hook() {
        if (!hooked) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            hooked = true;
        }
    }

    public void unhook() {
        removeAll();
        HandlerList.unregisterAll(this);
        hooked = false;
    }

    @Override
    public String pluginName() {
        return "Paper";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String status() {
        return "native entities (" + byId.size() + ")";
    }

    @Override
    public Optional<NpcHandle> spawn(NpcSpec spec) {
        Objects.requireNonNull(spec);
        if (spec.location().getWorld() == null) {
            return Optional.empty();
        }
        NpcHandle previous = byId.remove(spec.id());
        if (previous != null) {
            remove(previous);
        }
        var world = spec.location().getWorld();
        world.getChunkAt(spec.location()).load();
        for (Entity entity : world.getNearbyEntities(spec.location(), 2, 3, 2)) {
            String marker = entity.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
            if (spec.id().equals(marker)) {
                entity.remove();
            }
        }
        EntityType type = spec.entityType() == EntityType.PLAYER || !spec.entityType().isSpawnable() ? EntityType.VILLAGER : spec.entityType();
        Entity entity;
        try {
            entity = world.spawnEntity(spec.location(), type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setGravity(false);
                e.customName(spec.displayName());
                e.setCustomNameVisible(true);
                e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, spec.id());
                if (e instanceof LivingEntity living) {
                    living.setAI(false);
                    living.setCollidable(false);
                    living.setRemoveWhenFarAway(false);
                    living.setCanPickupItems(false);
                }
                if (e instanceof Mob mob) {
                    mob.setAware(false);
                }
                if (e instanceof Villager villager) {
                    villager.setProfession(professionFor(spec.id()));
                    villager.setVillagerLevel(2);
                }
            });
        } catch (RuntimeException e) {
            logger.warning("Native NPC '" + spec.id() + "' could not be spawned: " + e.getMessage());
            return Optional.empty();
        }
        if (!spec.model().isBlank()) {
            try {
                if (modelAttacher.apply(entity, spec.model())) {
                    entity.setCustomNameVisible(false);
                }
            } catch (RuntimeException e) {
                logger.warning("Model '" + spec.model() + "' for NPC '" + spec.id() + "' failed: " + e.getMessage());
            }
        }
        NpcHandle handle = new NpcHandle(spec.id(), entity.getUniqueId(), -1, false);
        byEntity.put(entity.getUniqueId(), handle);
        byId.put(spec.id(), handle);
        return Optional.of(handle);
    }

    private static Villager.Profession professionFor(String id) {
        return switch (id) {
            case "mama_bakewell" -> Villager.Profession.FARMER;
            case "gustave" -> Villager.Profession.TOOLSMITH;
            case "babette" -> Villager.Profession.WEAPONSMITH;
            case "king_frosting" -> Villager.Profession.CLERIC;
            default -> Villager.Profession.LIBRARIAN;
        };
    }

    @Override
    public void remove(NpcHandle handle) {
        if (handle == null) return;
        byId.remove(handle.id(), handle);
        byEntity.remove(handle.entityId());
        Entity entity = Bukkit.getEntity(handle.entityId());
        if (entity != null) {
            entity.remove();
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
        NpcHandle handle = byEntity.get(entity.getUniqueId());
        return handle == null ? Optional.empty() : Optional.of(handle.id());
    }

    @Override
    public boolean isNpc(Entity entity) {
        return entity != null && (byEntity.containsKey(entity.getUniqueId())
                || entity.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING));
    }

    @Override
    public int count() {
        return byId.size();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        NpcHandle handle = byEntity.get(event.getRightClicked().getUniqueId());
        if (handle == null) return;
        event.setCancelled(true);
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        dispatch(handle.id(), event.getPlayer(), false);
    }

    @EventHandler // pre-cancelled for protected entities – must not use ignoreCancelled
    public void onAttack(PrePlayerAttackEntityEvent event) {
        NpcHandle handle = byEntity.get(event.getAttacked().getUniqueId());
        if (handle == null) return;
        event.setCancelled(true);
        dispatch(handle.id(), event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isNpc(event.getEntity())) {
            event.setCancelled(true);
        }
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
}
