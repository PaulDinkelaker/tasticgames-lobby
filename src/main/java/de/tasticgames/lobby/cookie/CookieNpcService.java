package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Cookie world NPCs (native villager entities, AI off, invulnerable) with simple quests.
 * NPC identity/role comes from configuration – entities are re-created on start.
 * A ModelEngine/Citizens provider can replace the entity spawn without touching quest logic.
 */
public final class CookieNpcService implements Service, Listener {

    private final Plugin plugin;
    private final CookieConfiguration configuration;
    private final CookieRuntimeService runtime;
    private final CookieWorldService world;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final NamespacedKey npcKey;
    private final Map<UUID, String> entities = new HashMap<>();

    public CookieNpcService(Plugin plugin, CookieConfiguration configuration, CookieRuntimeService runtime, CookieWorldService world,
                            LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.world = Objects.requireNonNull(world);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
        this.npcKey = new NamespacedKey(plugin, "cookie-npc");
    }

    @Override
    public String id() {
        return "cookie-npc-service";
    }

    @Override
    public void start() {
        if (!configuration.runtime().npcsEnabled()) {
            return;
        }
        World w = world.world().orElse(null);
        if (w == null) {
            return;
        }
        for (Entity entity : w.getEntities()) {
            if (entity.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
        for (CookieConfiguration.Npc npc : configuration.npcs().values()) {
            Location location = npc.location().toLocation(w);
            Villager villager = w.spawn(location, Villager.class, v -> {
                v.setAI(false);
                v.setInvulnerable(true);
                v.setSilent(true);
                v.setPersistent(false);
                v.setCollidable(false);
                v.setRemoveWhenFarAway(false);
                v.customName(messages.get(de.tasticgames.localization.SupportedLanguage.ENGLISH, "cookie.npc." + npc.id(), Map.of()));
                v.setCustomNameVisible(true);
                v.setProfession(switch (npc.id()) {
                    case "mama_bakewell" -> Villager.Profession.FARMER;
                    case "gustave" -> Villager.Profession.TOOLSMITH;
                    case "babette" -> Villager.Profession.WEAPONSMITH;
                    default -> Villager.Profession.CLERIC;
                });
                v.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, npc.id());
            });
            entities.put(villager.getUniqueId(), npc.id());
        }
        logger.info("Spawned " + entities.size() + " cookie NPC(s).");
    }

    @Override
    public void stop() {
        for (UUID id : entities.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        entities.clear();
    }

    public int count() {
        return entities.size();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        String npcId = entities.get(event.getRightClicked().getUniqueId());
        if (npcId != null) {
            event.setCancelled(true);
            talk(event.getPlayer(), npcId);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (entities.containsKey(event.getAttacked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (entities.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void talk(Player player, String npcId) {
        CookieConfiguration.Npc npc = configuration.npcs().get(npcId);
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (npc == null || session == null) {
            return;
        }
        String quest = npc.quest().isBlank() ? npcId : npc.quest();
        CookieProfile profile = session.profile();
        String achievement = "npc_quest_" + quest;
        player.sendMessage(Component.text("[", NamedTextColor.DARK_GRAY)
                .append(messages.get(player, "cookie.npc." + npcId)).append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(messages.get(player, "cookie.npc.quest." + quest)));
        if (profile.achievements().contains(achievement)) {
            return;
        }
        boolean done = switch (quest) {
            case "starter" -> profile.totalClicks() >= 100 && profile.generatorCount("baker") >= 1;
            case "explore" -> profile.discoveredZones().size() >= 3;
            case "timed" -> profile.highestCombo() >= 3;
            case "prestige" -> profile.prestigeLevel() >= 1;
            default -> false;
        };
        if (!done) {
            messages.send(player, "cookie.npc.quest.pending");
            return;
        }
        long crumbs = switch (quest) {
            case "starter" -> 1;
            case "explore" -> 3;
            case "timed" -> 2;
            default -> 5;
        };
        profile.addAchievement(achievement);
        profile.addCrumbs(crumbs);
        profile.markDirty();
        session.touchDirty();
        messages.send(player, "cookie.npc.quest.done", Map.of("reward", crumbs + " crumbs"));
        sounds.play(player, "minecraft:entity.villager.celebrate", 1f, 1f);
        telemetry.event("cookie.quest_completed", player.getUniqueId(), Map.of("quest", quest, "npc", npcId));
    }
}
