package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.model.ZoneAccess;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The prestige-10 <em>open world</em>: world resolution (or development world creation), gated
 * enter/exit, zone gates, zone discovery, POI visits, fast travel. The main cookie itself lives
 * in the lobby (see {@link CookieClickService}).
 */
public final class CookieWorldService implements Service, Listener {

    private final java.util.function.Supplier<CookieConfiguration> configuration;
    private final CookieRuntimeService runtime;
    private final LobbyPlayerService players;
    private final LobbySpawnService spawn;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final Logger logger;
    private final List<Consumer<Player>> enterHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Consumer<Player>> exitHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean worldReady;

    public CookieWorldService(java.util.function.Supplier<CookieConfiguration> configuration, CookieRuntimeService runtime, LobbyPlayerService players, LobbySpawnService spawn,
                              LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry, MainThread mainThread, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.players = Objects.requireNonNull(players);
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
        CookieConfiguration.OpenWorld open = configuration.get().openWorld();
        if (!open.enabled()) {
            logger.info("Cookie open world disabled by configuration.");
            return;
        }
        World world = Bukkit.getWorld(open.name());
        if (world == null && open.createIfMissing()) {
            logger.info("Cookie open world '" + open.name() + "' not found – creating a flat development world.");
            world = new WorldCreator(open.name()).type(WorldType.FLAT).generateStructures(false).createWorld();
        }
        worldReady = world != null;
        if (!worldReady) {
            logger.warning("Cookie open world '" + open.name() + "' is not available – open-world features stay disabled until it loads.");
        } else {
            world.setSpawnLocation(open.entry().toLocation(world));
            logger.info("Cookie open world ready: " + world.getName() + " (prestige " + open.requiredPrestige() + "+, "
                    + configuration.get().zones().size() + " zones, " + configuration.get().pois().size() + " POIs).");
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

    public boolean enabled() {
        return configuration.get().openWorld().enabled();
    }

    public boolean worldReady() {
        return enabled() && (worldReady || Bukkit.getWorld(configuration.get().openWorld().name()) != null);
    }

    public Optional<World> world() {
        return enabled() ? Optional.ofNullable(Bukkit.getWorld(configuration.get().openWorld().name())) : Optional.empty();
    }

    /** Whether the world is the cookie open world. */
    public boolean isOpenWorld(World world) {
        return enabled() && world != null && world.getName().equals(configuration.get().openWorld().name());
    }

    public int requiredPrestige() {
        return configuration.get().openWorld().requiredPrestige();
    }

    public CookieConfiguration configuration() {
        return configuration.get();
    }

    /** Whether the player's profile may enter the open world (prestige gate). */
    public boolean mayEnter(CookieSession session) {
        return session != null && session.profile().prestigeLevel() >= requiredPrestige();
    }

    /** Enters the open world (loads the profile first, checks the prestige gate). */
    public void enter(Player player) {
        if (!enabled()) {
            messages.send(player, "cookie.world.missing");
            return;
        }
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
            if (!mayEnter(session)) {
                messages.send(player, "cookie.world.locked", Map.of("prestige", requiredPrestige()));
                sounds.error(player);
                return;
            }
            lobbyPlayer.mode(LobbyPlayer.Mode.COOKIE_WORLD);
            spawn.teleport(player, configuration.get().openWorld().entry().toLocation(world)).thenAccept(ok -> mainThread.run(() -> {
                if (!player.isOnline()) return;
                if (!ok) {
                    lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
                    sounds.error(player);
                    return;
                }
                messages.send(player, "cookie.world.entered");
                sounds.success(player);
                telemetry.event("cookie.world_entered", player.getUniqueId(), Map.of());
                enterHooks.forEach(h -> h.accept(player));
                updateZone(player, lobbyPlayer, player.getLocation(), true);
            }));
        }));
    }

    /** Leaves the open world back to the lobby spawn (saves, restores lobby state). */
    public void leave(Player player) {
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (!lobbyPlayer.inCookieWorld() && !isOpenWorld(player.getWorld())) {
            spawn.teleportToSpawn(player);
            return;
        }
        runtime.session(player.getUniqueId()).ifPresent(s -> runtime.save(s, true));
        spawn.teleportToSpawn(player).thenAccept(ok -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            if (!ok) {
                sounds.error(player);
                return;
            }
            markLeft(lobbyPlayer);
            exitHooks.forEach(h -> h.accept(player));
            messages.send(player, "cookie.world.left");
        }));
    }

    private void markLeft(LobbyPlayer lobbyPlayer) {
        lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
        lobbyPlayer.currentZoneId(null);
        lobbyPlayer.currentPoiId(null);
    }

    /** Fast travel to a discovered/allowed zone (enters the world when necessary). */
    public void travel(Player player, String zoneId) {
        CookieConfiguration.Zone zone = configuration.get().zones().get(zoneId);
        World world = world().orElse(null);
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (zone == null || world == null || session == null) {
            messages.send(player, "cookie.world.missing");
            return;
        }
        if (!mayEnter(session)) {
            messages.send(player, "cookie.world.locked", Map.of("prestige", requiredPrestige()));
            sounds.error(player);
            return;
        }
        ZoneAccess access = runtime.engine().canEnter(session.profile(), zoneId);
        if (!access.allowed()) {
            messages.send(player, "cookie.world.zone_locked", Map.of("prestige", access.requiredPrestige()));
            sounds.error(player);
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        boolean wasInside = lobbyPlayer.inCookieWorld();
        lobbyPlayer.mode(LobbyPlayer.Mode.COOKIE_WORLD);
        spawn.teleport(player, zone.entry().toLocation(world)).thenAccept(ok -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            if (!ok) {
                if (!wasInside) lobbyPlayer.mode(LobbyPlayer.Mode.LOBBY);
                sounds.error(player);
                return;
            }
            if (!wasInside) {
                enterHooks.forEach(h -> h.accept(player));
            }
            messages.send(player, "cookie.travel.travelled", Map.of("zone", zoneName(player, zoneId)));
            telemetry.event("cookie.fast_travel", player.getUniqueId(), Map.of("zone", zoneId));
            updateZone(player, lobbyPlayer, player.getLocation(), true);
        }));
    }

    public String zoneName(Player player, String zoneId) {
        return runtime.engine().catalog().zone(zoneId).map(z -> messages.contains(z.nameKey()) ? messages.raw(messages.languageOf(player), z.nameKey()) : z.displayName()).orElse(zoneId);
    }

    /** Void rescue inside the open world: back to the current zone entry (or the world entry). */
    public boolean rescue(Player player) {
        World world = world().orElse(null);
        if (world == null || !isOpenWorld(player.getWorld())) {
            return false;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        CookieConfiguration.Zone zone = lobbyPlayer.currentZoneId() == null ? null : configuration.get().zones().get(lobbyPlayer.currentZoneId());
        Location target = zone == null ? configuration.get().openWorld().entry().toLocation(world) : zone.entry().toLocation(world);
        spawn.teleport(player, target);
        return true;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (enabled() && event.getWorld().getName().equals(configuration.get().openWorld().name())) {
            worldReady = true;
            logger.info("Cookie open world '" + event.getWorld().getName() + "' loaded.");
        }
    }

    /** Players leaving the open world by other means (admin teleport, /lobby) lose the open-world mode. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        if (lobbyPlayer == null) return;
        if (isOpenWorld(event.getFrom()) && !isOpenWorld(player.getWorld()) && lobbyPlayer.inCookieWorld()) {
            markLeft(lobbyPlayer);
            exitHooks.forEach(h -> h.accept(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !isOpenWorld(event.getTo().getWorld())) {
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
            enterHooks.forEach(h -> h.accept(player));
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
                CookieConfiguration.Zone previous = lobbyPlayer.currentZoneId() == null ? null : configuration.get().zones().get(lobbyPlayer.currentZoneId());
                Location back = previous == null ? configuration.get().openWorld().entry().toLocation(location.getWorld()) : previous.gateReturn().toLocation(location.getWorld());
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
        for (CookieConfiguration.Poi poi : configuration.get().pois().values()) {
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
        for (CookieConfiguration.Zone zone : configuration.get().zones().values()) {
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
