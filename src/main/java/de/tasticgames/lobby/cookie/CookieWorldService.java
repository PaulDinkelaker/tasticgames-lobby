package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.model.ZoneAccess;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.service.Service;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Cookie open world: world resolution (or development world creation), enter/exit, zone gates,
 * zone discovery, POI visits, fast travel.
 */
public final class CookieWorldService implements Service, Listener {

    private final CookieConfiguration configuration;
    private final CookieRuntimeService runtime;
    private final LobbyPlayerService players;
    private final LobbyItemService items;
    private final LobbySpawnService spawn;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final Logger logger;
    private final java.util.List<Consumer<Player>> enterHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<Consumer<Player>> exitHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean worldReady;

    public CookieWorldService(CookieConfiguration configuration, CookieRuntimeService runtime, LobbyPlayerService players, LobbyItemService items,
                              LobbySpawnService spawn, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry,
                              MainThread mainThread, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.players = Objects.requireNonNull(players);
        this.items = Objects.requireNonNull(items);
        this.spawn = Objects.requireNonNull(spawn);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cookie-world-service";
    }

    @Override
    public void start() {
        World world = Bukkit.getWorld(configuration.world().name());
        if (world == null && configuration.world().createIfMissing() && !configuration.world().useLobbyWorld()) {
            logger.info("Cookie world '" + configuration.world().name() + "' not found – creating a flat development world.");
            world = new WorldCreator(configuration.world().name()).type(WorldType.FLAT).generateStructures(false).createWorld();
        }
        worldReady = world != null;
        if (!worldReady) {
            logger.warning("Cookie world '" + configuration.world().name() + "' is not available – cookie world features are disabled until it loads.");
        } else {
            world.setSpawnLocation(configuration.world().entry().toLocation(world));
            logger.info("Cookie world ready: " + world.getName() + " (" + configuration.zones().size() + " zones, " + configuration.pois().size() + " POIs).");
        }
    }

    @Override
    public void stop() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
            if (lobbyPlayer != null && lobbyPlayer.inCookieWorld()) {
                lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
            }
        }
    }

    public void onEnter(Consumer<Player> hook) { enterHooks.add(hook); }
    public void onExit(Consumer<Player> hook) { exitHooks.add(hook); }

    public boolean worldReady() {
        return worldReady || Bukkit.getWorld(configuration.world().name()) != null;
    }

    public Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(configuration.world().name()));
    }

    public boolean isCookieWorld(World world) {
        return world != null && world.getName().equals(configuration.world().name());
    }

    public CookieConfiguration configuration() {
        return configuration;
    }

    /** Enters the cookie world (loads the profile first). */
    public void enter(Player player) {
        World world = world().orElse(null);
        if (world == null) {
            messages.send(player, "cookie.world.missing");
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        runtime.load(player.getUniqueId()).whenComplete((session, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            if (throwable != null) {
                messages.send(player, "cookie.unavailable");
                sounds.error(player);
                return;
            }
            lobbyPlayer.mode(LobbyPlayer.Mode.COOKIE_WORLD);
            spawn.teleport(player, configuration.world().entry().toLocation(world)).thenAccept(ok -> mainThread.run(() -> {
                if (!player.isOnline()) return;
                items.giveItems(player, lobbyPlayer);
                messages.send(player, "cookie.world.entered");
                sounds.success(player);
                telemetry.event("cookie.world_entered", player.getUniqueId(), Map.of());
                enterHooks.forEach(h -> h.accept(player));
                updateZone(player, lobbyPlayer, player.getLocation(), true);
            }));
        }));
    }

    /** Leaves the cookie world back to the lobby spawn (saves, restores lobby state). */
    public void leave(Player player) {
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (!lobbyPlayer.inCookieWorld() && !isCookieWorld(player.getWorld())) {
            spawn.teleportToSpawn(player);
            return;
        }
        lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
        lobbyPlayer.currentZoneId(null);
        lobbyPlayer.currentPoiId(null);
        runtime.session(player.getUniqueId()).ifPresent(s -> runtime.save(s, true));
        exitHooks.forEach(h -> h.accept(player));
        spawn.teleportToSpawn(player).thenAccept(ok -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            items.giveItems(player, lobbyPlayer);
            messages.send(player, "cookie.world.left");
        }));
    }

    /** Fast travel to a discovered/allowed zone. */
    public void travel(Player player, String zoneId) {
        CookieConfiguration.Zone zone = configuration.zones().get(zoneId);
        World world = world().orElse(null);
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (zone == null || world == null || session == null) {
            messages.send(player, "cookie.world.missing");
            return;
        }
        ZoneAccess access = runtime.engine().canEnter(session.profile(), zoneId);
        if (!access.allowed()) {
            messages.send(player, "cookie.world.zone_locked", Map.of("prestige", access.requiredPrestige()));
            sounds.error(player);
            return;
        }
        players.getOrCreate(player).mode(LobbyPlayer.Mode.COOKIE_WORLD);
        spawn.teleport(player, zone.entry().toLocation(world)).thenAccept(ok -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            messages.send(player, "cookie.travel.travelled", Map.of("zone", zoneName(player, zoneId)));
            telemetry.event("cookie.fast_travel", player.getUniqueId(), Map.of("zone", zoneId));
        }));
    }

    public String zoneName(Player player, String zoneId) {
        return runtime.engine().catalog().zone(zoneId).map(z -> messages.contains(z.nameKey()) ? messages.raw(messages.languageOf(player), z.nameKey()) : z.displayName()).orElse(zoneId);
    }

    /** Void rescue inside the cookie world: back to the current zone entry (or the world entry). */
    public boolean rescue(Player player) {
        World world = world().orElse(null);
        if (world == null || !isCookieWorld(player.getWorld())) {
            return false;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        CookieConfiguration.Zone zone = lobbyPlayer.currentZoneId() == null ? null : configuration.zones().get(lobbyPlayer.currentZoneId());
        Location target = zone == null ? configuration.world().entry().toLocation(world) : zone.entry().toLocation(world);
        spawn.teleport(player, target);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !isCookieWorld(event.getTo().getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (lobbyPlayer.buildMode() || lobbyPlayer.teleporting()) {
            return;
        }
        if (!lobbyPlayer.inCookieWorld()) {
            // player got here by other means (admin tp): treat as entering
            lobbyPlayer.mode(LobbyPlayer.Mode.COOKIE_WORLD);
            runtime.load(player.getUniqueId());
        }
        updateZone(player, lobbyPlayer, event.getTo(), false);
        updatePoi(player, lobbyPlayer, event.getTo());
    }

    private void updateZone(Player player, LobbyPlayer lobbyPlayer, Location location, boolean forceTitle) {
        String zoneId = zoneAt(location);
        if (zoneId == null) {
            return;
        }
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }
        if (!zoneId.equals(lobbyPlayer.currentZoneId()) || forceTitle) {
            ZoneAccess access = runtime.engine().canEnter(session.profile(), zoneId);
            if (!access.allowed()) {
                CookieConfiguration.Zone previous = lobbyPlayer.currentZoneId() == null ? null : configuration.zones().get(lobbyPlayer.currentZoneId());
                Location back = previous == null ? configuration.world().entry().toLocation(location.getWorld()) : previous.gateReturn().toLocation(location.getWorld());
                spawn.teleport(player, back);
                messages.send(player, "cookie.world.zone_locked", Map.of("prestige", access.requiredPrestige()));
                sounds.error(player);
                return;
            }
            lobbyPlayer.currentZoneId(zoneId);
            String name = zoneName(player, zoneId);
            boolean discovered = runtime.engine().discover(session.profile(), zoneId);
            if (discovered) {
                messages.send(player, "cookie.world.zone_discovered", Map.of("zone", name));
                sounds.play(player, "minecraft:ui.toast.challenge_complete", 0.8f, 1.2f);
                telemetry.event("cookie.zone_discovered", player.getUniqueId(), Map.of("zone", zoneId));
                List<String> unlocked = runtime.engine().evaluateAchievements(session.profile());
                unlocked.forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", achievementName(player, a))));
            }
            player.showTitle(Title.title(messages.get(player, "cookie.world.zone_title", Map.of("zone", name)), net.kyori.adventure.text.Component.empty(),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1500), Duration.ofMillis(500))));
        }
    }

    private void updatePoi(Player player, LobbyPlayer lobbyPlayer, Location location) {
        for (CookieConfiguration.Poi poi : configuration.pois().values()) {
            if (!poi.location().world().equals(location.getWorld().getName())) continue;
            double dx = poi.location().x() - location.getX();
            double dy = poi.location().y() - location.getY();
            double dz = poi.location().z() - location.getZ();
            if (dx * dx + dy * dy + dz * dz <= poi.radius() * poi.radius()) {
                if (!poi.id().equals(lobbyPlayer.currentPoiId())) {
                    lobbyPlayer.currentPoiId(poi.id());
                    telemetry.event("cookie.poi_visited", player.getUniqueId(), Map.of("poi", poi.id(), "type", poi.type()));
                }
                return;
            }
        }
        lobbyPlayer.currentPoiId(null);
    }

    public String zoneAt(Location location) {
        for (CookieConfiguration.Zone zone : configuration.zones().values()) {
            if (zone.region().contains(location)) {
                return zone.id();
            }
        }
        return null;
    }

    public String achievementName(Player player, String id) {
        return runtime.engine().catalog().achievement(id).map(a -> messages.contains(a.nameKey()) ? messages.raw(messages.languageOf(player), a.nameKey()) : id).orElse(id);
    }

    public List<ZoneDefinition> zoneDefinitions() {
        return runtime.engine().catalog().zones();
    }
}
