package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.integration.npc.NpcProvider;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Cookie quest NPCs (Citizens when installed, native entities otherwise). Identity/role come from
 * configuration; entities are (re)created on start / layout reload; quest logic is backend agnostic.
 */
public final class CookieNpcService implements Service, Listener {

    private final Supplier<CookieConfiguration> configuration;
    private final CookieRuntimeService runtime;
    private final NpcProvider npcs;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final Map<String, NpcProvider.NpcHandle> handles = new LinkedHashMap<>();
    private final java.util.Set<String> pendingWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CookieNpcService(Supplier<CookieConfiguration> configuration, CookieRuntimeService runtime, NpcProvider npcs, LobbyMessages messages,
                            LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.npcs = Objects.requireNonNull(npcs);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cookie-npc-service";
    }

    @Override
    public void start() {
        npcs.onClick((npcId, player, left) -> {
            if (configuration.get().npcs().list().containsKey(npcId)) {
                talk(player, npcId);
            }
        });
        respawn();
    }

    @Override
    public void stop() {
        for (NpcProvider.NpcHandle handle : handles.values()) {
            npcs.remove(handle);
        }
        handles.clear();
    }

    public int count() {
        return handles.size();
    }

    public String backend() {
        return npcs.pluginName();
    }

    /** (Re)creates every configured NPC (worlds not loaded yet are retried on WorldLoadEvent). */
    public void respawn() {
        for (NpcProvider.NpcHandle handle : handles.values()) {
            npcs.remove(handle);
        }
        handles.clear();
        pendingWorlds.clear();
        CookieConfiguration.Npcs config = configuration.get().npcs();
        if (!config.enabled()) {
            return;
        }
        for (CookieConfiguration.Npc npc : config.list().values()) {
            spawn(npc);
        }
        logger.info("Cookie NPCs: " + handles.size() + "/" + config.list().size() + " spawned via " + npcs.pluginName()
                + (pendingWorlds.isEmpty() ? "" : " (waiting for worlds " + pendingWorlds + ")"));
    }

    private void spawn(CookieConfiguration.Npc npc) {
        World world = Bukkit.getWorld(npc.location().world());
        if (world == null) {
            pendingWorlds.add(npc.location().world());
            return;
        }
        Location location = npc.location().toLocation(world);
        Component name = messages.get(de.tasticgames.localization.SupportedLanguage.ENGLISH, "cookie.npc." + npc.id(), Map.of());
        NpcProvider.NpcSpec spec = new NpcProvider.NpcSpec(npc.id(), name, location, npc.entityType(), npc.skin(), npc.model(), npc.citizensId());
        npcs.spawn(spec).ifPresentOrElse(handle -> handles.put(npc.id(), handle),
                () -> logger.warning("Cookie NPC '" + npc.id() + "' could not be spawned via " + npcs.pluginName() + "."));
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (pendingWorlds.remove(event.getWorld().getName())) {
            for (CookieConfiguration.Npc npc : configuration.get().npcs().list().values()) {
                if (npc.location().world().equals(event.getWorld().getName()) && !handles.containsKey(npc.id())) {
                    spawn(npc);
                }
            }
        }
    }

    private void talk(Player player, String npcId) {
        CookieConfiguration.Npc npc = configuration.get().npcs().list().get(npcId);
        if (npc == null) {
            return;
        }
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        String quest = npc.quest().isBlank() ? npcId : npc.quest();
        player.sendMessage(Component.text("[", NamedTextColor.DARK_GRAY)
                .append(messages.get(player, "cookie.npc." + npcId)).append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(messages.get(player, "cookie.npc.quest." + quest)));
        if (session == null) {
            messages.send(player, "cookie.unavailable");
            return;
        }
        CookieProfile profile = session.profile();
        String achievement = "npc_quest_" + quest;
        if (profile.achievements().contains(achievement)) {
            return;
        }
        boolean done = switch (quest) {
            case "starter" -> profile.totalClicks() >= 100 && profile.generatorCount("baker") >= 1;
            case "explore" -> profile.totalGenerators() >= 25;
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
        List<String> unlocked = runtime.engine().evaluateAchievements(profile);
        unlocked.forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", CookieNames.achievement(messages, runtime.engine(), player, a))));
    }
}
