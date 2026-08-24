package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.ClickResult;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.integration.mob.MobProvider;
import de.tasticgames.lobby.integration.model.ModelProvider;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.service.Service;
import de.tasticgames.settings.CoreSettings;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The physical MAIN COOKIE in the lobby: visual backend (MythicMobs mob → ModelEngine model →
 * native item display), a click hitbox, floating label, self-healing respawn (chunk ticket +
 * periodic check + world load), click handling with server-side rewards, combo feedback and the
 * proximity actionbar. Left click bakes, right click (or the cookie item / {@code /cookie}) opens the
 * cookie menu. Generators only produce while the player stands inside the cookie zone
 * ({@code main-cookie.zone-radius}) or in the open world.
 * <p>
 * ModelEngine registers its blueprints after the plugins enabled: when the visual needs a model
 * (MythicMobs mob with a {@code model{...}} skill or a direct ModelEngine model) the spawn waits for
 * {@link ModelProvider#modelsReady()} (bounded by {@link #MODEL_WAIT_TICKS}) and the binding result is
 * logged explicitly (OK / FAILED with reason) instead of guessing.
 */
public final class CookieClickService implements Service, Listener {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final Supplier<CookieConfiguration> configuration;
    private final CookieRuntimeService runtime;
    private final MobProvider mobs;
    private final ModelProvider models;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final Logger logger;
    private final NamespacedKey markerKey;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();
    private final Map<UUID, Long> unavailableNoticeAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastClickTick = new ConcurrentHashMap<>();
    private volatile Consumer<Player> menuOpener = p -> { };
    private volatile CookieProgressListener progress = CookieProgressListener.NONE;
    /** Every cookie of the lobby; the first one is the main cookie of the configuration. */
    private final List<Spot> spots = new java.util.concurrent.CopyOnWriteArrayList<>();
    private org.bukkit.scheduler.BukkitTask clickReportTask;
    private org.bukkit.scheduler.BukkitTask healTask;
    private long lastLoadWarningAt;
    private volatile boolean waitingForModels;

    /**
     * One cookie: its entities, its chunk ticket and its own ModelEngine binding state. Several of
     * them stand in the lobby so a busy plaza spreads out instead of crowding a single model.
     */
    private static final class Spot {

        private final CookieConfiguration.Point point;
        private volatile UUID interactionId;
        private volatile UUID visualId;
        private volatile UUID labelId;
        private volatile Chunk ticketChunk;
        private volatile String backend = "none";
        private volatile int bindingGeneration;
        private volatile int bindingAttempts;

        private Spot(CookieConfiguration.Point point) {
            this.point = point;
        }

        private boolean alive() {
            return interactionId != null && Bukkit.getEntity(interactionId) != null;
        }

        private Entity visual() {
            return visualId == null ? null : Bukkit.getEntity(visualId);
        }

        private boolean owns(UUID id) {
            return id.equals(interactionId) || id.equals(visualId) || id.equals(labelId);
        }
    }

    /** Upper bound for waiting on ModelEngine's model registration before spawning without it (ticks). */
    static final long MODEL_WAIT_TICKS = 20L * 90;
    /** Respawns of the visual when the mob's model did not bind (MythicMobs/ModelEngine still initialising). */
    static final int MAX_BINDING_ATTEMPTS = 3;
    static final long BINDING_RETRY_TICKS = 60L;

    public CookieClickService(Plugin plugin, TasticCoreApi coreApi, Supplier<CookieConfiguration> configuration, CookieRuntimeService runtime,
                              MobProvider mobs, ModelProvider models, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry,
                              MainThread mainThread, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.mobs = Objects.requireNonNull(mobs);
        this.models = Objects.requireNonNull(models);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
        this.markerKey = new NamespacedKey(plugin, "cookie-entity");
    }

    @Override
    public String id() {
        return "cookie-click-service";
    }

    @Override
    public void start() {
        models.onInteract((player, base, left) -> {
            for (Spot spot : spots) {
                if (base.equals(spot.visualId)) {
                    handleClick(player, left);
                    return true;
                }
            }
            return false;
        });
        models.onModelsReady(this::onModelsReady);
        mobs.onReload(this::onMobsReloaded);
        if (requiresModels() && !models.modelsReady()) {
            waitingForModels = true;
            CookieConfiguration.MainCookie main = configuration.get().mainCookie();
            logger.info("Main cookie: waiting for ModelEngine model registration before spawning "
                    + (main.mythicMobsType().isBlank() ? "model '" + main.model() + "'" : "MythicMobs mob '" + main.mythicMobsType() + "'") + "...");
            mainThread.later(MODEL_WAIT_TICKS, () -> {
                if (waitingForModels && !spawned()) {
                    waitingForModels = false;
                    logger.warning("ModelEngine did not finish its model registration within " + (MODEL_WAIT_TICKS / 20) + "s – spawning the main cookie anyway.");
                    spawnMainCookie();
                }
            });
        } else {
            // first server tick (never inside onEnable): MythicMobs/ModelEngine finish their delayed initialisation first
            mainThread.later(1L, () -> {
                if (!spawned()) {
                    spawnMainCookie();
                }
            });
        }
        clickReportTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reportClicks, 20L * 60, 20L * 60);
        healTask = Bukkit.getScheduler().runTaskTimer(plugin, this::heal, 20L * 5, 20L * 5);
    }

    @Override
    public void stop() {
        if (clickReportTask != null) clickReportTask.cancel();
        if (healTask != null) healTask.cancel();
        reportClicks();
        removeMainCookie();
    }

    public void setMenuOpener(Consumer<Player> opener) {
        this.menuOpener = Objects.requireNonNull(opener);
    }

    /** Consumer of the baking progress (season pass XP/metrics); {@link CookieProgressListener#NONE} by default. */
    public void setProgressListener(CookieProgressListener listener) {
        this.progress = Objects.requireNonNull(listener);
    }

    public NamespacedKey markerKey() {
        return markerKey;
    }

    public CookieNumberFormatter formatter() {
        return formatter;
    }

    /** Active visual backend: mythicmobs, modelengine, native or none. */
    public String backend() {
        Spot first = spots.isEmpty() ? null : spots.getFirst();
        return first == null ? "none" : first.backend;
    }

    /** Whether every configured cookie stands. */
    public boolean spawned() {
        if (spots.isEmpty() || spots.size() != configuration.get().mainCookie().locations().size()) {
            return false;
        }
        for (Spot spot : spots) {
            if (!spot.alive()) {
                return false;
            }
        }
        return true;
    }

    /** How many cookies stand in the lobby right now. */
    public int cookieCount() {
        return spots.size();
    }

    public Location mainCookieLocation() {
        CookieConfiguration.Point point = configuration.get().mainCookie().location();
        World world = Bukkit.getWorld(point.world());
        return world == null ? null : point.toLocation(world);
    }

    /** The cookie closest to the player - the one a click, an animation or a particle belongs to. */
    private Spot nearestSpot(Player player) {
        Spot nearest = null;
        double best = Double.MAX_VALUE;
        for (Spot spot : spots) {
            Entity visual = spot.visual();
            Location at = visual != null ? visual.getLocation() : locationOf(spot);
            if (at == null || at.getWorld() == null || !at.getWorld().equals(player.getWorld())) {
                continue;
            }
            double distance = at.distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = spot;
            }
        }
        return nearest;
    }

    private Location locationOf(Spot spot) {
        World world = Bukkit.getWorld(spot.point.world());
        return world == null ? null : spot.point.toLocation(world);
    }

    /** (Re)spawns every configured cookie; removes stale markers first. */
    public synchronized void spawnMainCookie() {
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        World w = Bukkit.getWorld(main.world());
        if (w == null) {
            long now = System.currentTimeMillis();
            if (now - lastLoadWarningAt > 60_000) {
                lastLoadWarningAt = now;
                logger.warning("Main cookie world '" + main.world() + "' is not loaded – the cookies spawn as soon as the world is available.");
            }
            return;
        }
        removeMainCookie();
        waitingForModels = false;
        List<CookieConfiguration.Point> points = main.locations();
        for (CookieConfiguration.Point point : points) {
            Spot spot = new Spot(point);
            spots.add(spot);
            spawnSpot(main, w, spot);
        }
        logger.info(points.size() + " cookie" + (points.size() == 1 ? "" : "s") + " spawned in " + w.getName()
                + " (backend " + backend() + ", zone radius " + (int) main.zoneRadius() + ").");
    }

    /** Spawns the visual, the click hitbox and the label of a single cookie. */
    private void spawnSpot(CookieConfiguration.MainCookie main, World w, Spot spot) {
        Location location = spot.point.toLocation(w);
        Chunk chunk = w.getChunkAt(location);
        chunk.load();
        chunk.addPluginChunkTicket(plugin);
        spot.ticketChunk = chunk;
        for (Entity entity : w.getNearbyEntities(location, 6, 6, 6)) {
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) {
                entity.remove();
            } else if (!main.mythicMobsType().isBlank() && mobs.available()
                    && mobs.mobType(entity).map(t -> t.equalsIgnoreCase(main.mythicMobsType())).orElse(false)) {
                // a manually spawned (persistent) copy of the cookie mob – the plugin owns the cookies
                logger.info("Removing manually spawned MythicMobs '" + main.mythicMobsType() + "' next to a cookie.");
                mobs.remove(entity);
            }
        }

        // 1. MythicMobs mob (carries the ModelEngine model configured by the builders)
        Entity visual = null;
        String usedBackend = "native";
        if (!main.mythicMobsType().isBlank() && mobs.available()) {
            if (mobs.hasMobType(main.mythicMobsType())) {
                visual = mobs.spawn(main.mythicMobsType(), location.clone()).orElse(null);
                if (visual != null) {
                    usedBackend = "mythicmobs";
                }
            } else {
                logger.warning("MythicMobs type '" + main.mythicMobsType() + "' for the cookie does not exist – falling back.");
            }
        }
        // 2. ModelEngine model on an invisible base entity
        if (visual == null && !main.model().isBlank() && models.available()) {
            if (models.hasModel(main.model())) {
                ArmorStand base = w.spawn(location.clone(), ArmorStand.class, e -> {
                    e.setInvisible(true);
                    e.setMarker(false);
                    e.setGravity(false);
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setCollidable(false);
                    e.setBasePlate(false);
                    e.setCanTick(true);
                });
                if (models.attach(base, main.model())) {
                    visual = base;
                    usedBackend = "modelengine";
                } else {
                    base.remove();
                }
            } else {
                logger.warning("ModelEngine model '" + main.model() + "' for the cookie does not exist – falling back to the native display.");
            }
        }
        // 3. native item display
        if (visual == null) {
            visual = w.spawn(location.clone().add(0, 0.7, 0), ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.COOKIE));
                e.setBillboard(Display.Billboard.VERTICAL);
                e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(2.0f, 2.0f, 2.0f), new AxisAngle4f(0, 0, 0, 1)));
                e.setPersistent(false);
            });
            usedBackend = "native";
        }
        visual.setPersistent(false);
        visual.setInvulnerable(true);
        visual.setSilent(true);
        if (visual instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
            if (!"mythicmobs".equals(usedBackend)) {
                // MythicMobs mobs keep the behaviour of their mob file (AI selectors, movement speed)
                living.setAI(false);
                living.setGravity(false);
            }
        }
        visual.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-visual");
        spot.visualId = visual.getUniqueId();
        spot.backend = usedBackend;
        if (!"native".equals(usedBackend)) {
            // MythicMobs applies its model a tick after spawning: hide the base entity (e.g. the pig) now and again shortly after
            hideBase(spot, visual);
            mainThread.later(2L, () -> hideBase(spot, spot.visual()));
            int generation = ++spot.bindingGeneration;
            mainThread.later(20L, () -> {
                hideBase(spot, spot.visual());
                reportBinding(spot, generation);
            });
        }

        Interaction interaction = w.spawn(location.clone(), Interaction.class, e -> {
            e.setInteractionWidth((float) main.hitboxWidth());
            e.setInteractionHeight((float) main.hitboxHeight());
            e.setResponsive(true);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-interaction");
        });
        spot.interactionId = interaction.getUniqueId();

        if (main.label()) {
            TextDisplay label = w.spawn(location.clone().add(0, main.hitboxHeight() + 0.3, 0), TextDisplay.class, e -> {
                e.text(Component.text("🍪 ", NamedTextColor.GOLD).append(Component.text("Cookie Clicker", NamedTextColor.WHITE))
                        .append(Component.newline()).append(Component.text("Click me!", NamedTextColor.GRAY)));
                e.setBillboard(Display.Billboard.CENTER);
                e.setSeeThrough(false);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-label");
            });
            spot.labelId = label.getUniqueId();
        }
        logger.info("Cookie spawned in " + w.getName() + " at " + location.getBlockX() + "," + location.getBlockY() + ","
                + location.getBlockZ() + " (backend " + usedBackend + ").");
    }

    private synchronized void removeMainCookie() {
        for (Spot spot : spots) {
            removeSpot(spot);
        }
        spots.clear();
    }

    private void removeSpot(Spot spot) {
        Entity visual = spot.visual();
        if (visual != null) {
            try {
                if ("modelengine".equals(spot.backend)) {
                    models.detach(visual);
                } else if ("mythicmobs".equals(spot.backend)) {
                    mobs.remove(visual);
                }
            } catch (RuntimeException e) {
                logger.warning("Cookie visual could not be removed cleanly: " + e.getMessage());
            }
            if (visual.isValid()) {
                visual.remove();
            }
        }
        for (UUID id : new UUID[]{spot.interactionId, spot.labelId}) {
            if (id == null) continue;
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        if (spot.ticketChunk != null) {
            spot.ticketChunk.removePluginChunkTicket(plugin);
            spot.ticketChunk = null;
        }
        spot.interactionId = null;
        spot.visualId = null;
        spot.labelId = null;
        spot.backend = "none";
    }

    /**
     * The visual base entity (MythicMobs mob / ModelEngine base) must never be visible: the model
     * plugin hides it when it knows the entity, and the entity itself is made invisible as a
     * belt-and-braces measure (the ModelEngine model is rendered separately).
     */
    private void hideBase(Spot spot, Entity visual) {
        if (visual == null || !visual.isValid() || "native".equals(spot.backend)) {
            return;
        }
        // ModelEngine hides the base entity itself (setBaseEntityVisible). Bukkit invisibility must NOT be used
        // while a model is attached: ModelEngine mirrors the base entity's invisibility onto the model.
        boolean hidden = models.available() && models.hideBase(visual);
        boolean modeled = models.available() && models.isModeled(visual);
        if (visual instanceof LivingEntity living) {
            living.setInvisible(!modeled && !hidden);
        }
        visual.setCustomNameVisible(false);
    }

    /** Whether the configured visual depends on ModelEngine blueprints (MythicMobs mob with a model, or a direct model). */
    private boolean requiresModels() {
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        if (!models.available()) {
            return false;
        }
        boolean mythic = !main.mythicMobsType().isBlank() && mobs.available();
        return mythic || !main.model().isBlank();
    }

    /** ModelEngine finished (re-)registering its blueprints: spawn a waiting cookie or re-bind a lost model. */
    private void onModelsReady() {
        if (!spawned()) {
            if (waitingForModels || requiresModels()) {
                spawnMainCookie();
            }
            return;
        }
        for (Spot spot : spots) {
            Entity visual = spot.visual();
            if (visual != null && !"native".equals(spot.backend) && !models.isModeled(visual)) {
                // models were reloaded (/meg reload) and the mob lost its model – a fresh spawn re-applies the mob's model skill
                logger.info("Cookies: ModelEngine models re-registered, respawning the visuals to re-bind the models.");
                spawnMainCookie();
                return;
            }
        }
    }

    /** MythicMobs finished a reload: a spawned mob whose model got lost (or never applied) is respawned once. */
    private void onMobsReloaded() {
        if (!"mythicmobs".equals(backend()) || !models.available() || !spawned()) {
            return;
        }
        for (Spot spot : spots) {
            Entity visual = spot.visual();
            if (visual != null && !models.isModeled(visual)) {
                logger.info("Cookies: MythicMobs reloaded and a visual carries no ModelEngine model – respawning to re-apply the mob's model.");
                spot.bindingAttempts = 0;
                spawnMainCookie();
                return;
            }
        }
    }

    /**
     * One explicit line per spawn: is the ModelEngine model bound to the visual or not (and why). A
     * missing binding is retried a few times by respawning the visual (the mob's spawn skills run
     * again) before the failure is reported.
     */
    private void reportBinding(Spot spot, int generation) {
        if (generation != spot.bindingGeneration || !models.available() || "native".equals(spot.backend)) {
            return;
        }
        Entity visual = spot.visual();
        if (visual == null) {
            return;
        }
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        boolean modeled = models.isModeled(visual);
        String subject = "mythicmobs".equals(spot.backend) ? "MythicMobs mob '" + main.mythicMobsType() + "'" : "model '" + main.model() + "'";
        if (modeled) {
            logger.info("Cookie ModelEngine binding: OK (" + subject + (spot.bindingAttempts > 0 ? ", after " + spot.bindingAttempts + " retr" + (spot.bindingAttempts == 1 ? "y" : "ies") : "") + ").");
            spot.bindingAttempts = 0;
            return;
        }
        if (models.modelsReady() && spot.bindingAttempts < MAX_BINDING_ATTEMPTS) {
            spot.bindingAttempts++;
            logger.info("Cookie ModelEngine binding not applied yet (" + subject + ", attempt " + spot.bindingAttempts + "/" + MAX_BINDING_ATTEMPTS
                    + ") – respawning the visual in " + (BINDING_RETRY_TICKS / 20) + " s.");
            int expected = generation;
            mainThread.later(BINDING_RETRY_TICKS, () -> {
                Entity current = spot.visual();
                if (spot.bindingGeneration == expected && (current == null || !models.isModeled(current))) {
                    spawnMainCookie();
                }
            });
            return;
        }
        String reason;
        if (!models.modelsReady()) {
            reason = "ModelEngine has not registered its models yet";
        } else if ("mythicmobs".equals(spot.backend)) {
            reason = main.model().isBlank()
                    ? "the mob carries no ModelEngine model – check its model{mid=...} skill and that the blueprint exists"
                    : (models.hasModel(main.model()) ? "blueprint '" + main.model() + "' exists but the mob's model{...} skill did not apply it"
                    : "blueprint '" + main.model() + "' missing");
        } else {
            reason = "blueprint '" + main.model() + "' " + (models.hasModel(main.model()) ? "exists but could not be attached" : "missing");
        }
        logger.warning("Cookie ModelEngine binding: FAILED (" + subject + ") – " + reason + ". The base entity stays invisible; clicks keep working.");
    }

    /** Self-heal: respawn when an entity vanished (chunk unload, /kill @e, world reload). */
    private void heal() {
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        World w = Bukkit.getWorld(main.world());
        if (w == null || waitingForModels) {
            return;
        }
        if (spots.size() != main.locations().size()) {
            // the configuration changed (a cookie was added or removed) - rebuild the whole set
            logger.info("Cookie layout changed – respawning all cookies.");
            spawnMainCookie();
            return;
        }
        for (Spot spot : spots) {
            Entity visual = spot.visual();
            if (!spot.alive() || visual == null) {
                logger.info("Cookie entities missing at " + (int) spot.point.x() + "," + (int) spot.point.y() + ","
                        + (int) spot.point.z() + " – respawning that cookie.");
                removeSpot(spot);
                spawnSpot(main, w, spot);
                continue;
            }
            hideBase(spot, visual);
        }
    }

    /** Whether the player stands inside the cookie zone around the main cookie. */
    public boolean inZone(Player player) {
        return player != null && configuration.get().mainCookie().inZone(player.getLocation());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getName().equals(configuration.get().mainCookie().world()) && !spawned() && !waitingForModels) {
            mainThread.later(1L, this::spawnMainCookie);
        }
    }

    private boolean isMainCookie(Entity entity) {
        if (entity == null) return false;
        UUID id = entity.getUniqueId();
        for (Spot spot : spots) {
            if (spot.owns(id)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!isMainCookie(event.getRightClicked())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleClick(event.getPlayer(), false);
    }

    /**
     * Left click. Paper fires this event pre-cancelled ({@code willAttack=false}) for Interaction
     * entities and model hitboxes, so it must NOT use ignoreCancelled.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        if (!isMainCookie(event.getAttacked())) return;
        event.setCancelled(true);
        handleClick(event.getPlayer(), true);
    }

    /** Fallback when another plugin (e.g. the model hitbox) redirects the attack as real damage. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!isMainCookie(event.getEntity())) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player player) {
            handleClick(player, true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isMainCookie(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Fallback for hitboxes the server does not see as entities: a left-click swing whose eye ray
     * hits the cookie's box counts as a click (once per tick, so entity events never double count).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        double reach = 5.0;
        double half = main.hitboxWidth() / 2.0;
        for (Spot spot : spots) {
            Location cookie = locationOf(spot);
            if (cookie == null || !player.getWorld().equals(cookie.getWorld())) continue;
            if (player.getEyeLocation().distanceSquared(cookie) > (reach + main.hitboxHeight()) * (reach + main.hitboxHeight())) continue;
            BoundingBox box = new BoundingBox(cookie.getX() - half, cookie.getY(), cookie.getZ() - half,
                    cookie.getX() + half, cookie.getY() + main.hitboxHeight(), cookie.getZ() + half);
            RayTraceResult hit = box.rayTrace(player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), reach);
            if (hit == null) continue;
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
            }
            handleClick(player, true);
            return;
        }
    }

    private void handleClick(Player player, boolean leftClick) {
        if (leftClick) {
            click(player);
        } else {
            menuOpener.accept(player);
        }
    }

    /** Server-side click: validate, reward, feedback. Works wherever the main cookie stands. */
    public void click(Player player) {
        int tick = Bukkit.getCurrentTick();
        Integer last = lastClickTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return; // the same swing reached us through two events (entity + ray trace)
        }
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            if (!runtime.available()) {
                notifyUnavailable(player);
                return;
            }
            runtime.load(player.getUniqueId()).whenComplete((s, t) -> {
                if (t != null) {
                    logger.warning("Cookie profile load failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(t));
                    mainThread.run(() -> notifyUnavailable(player));
                } else {
                    mainThread.run(() -> { if (player.isOnline()) click(player); });
                }
            });
            return;
        }
        if (session.paused()) {
            messages.send(player, "cookie.paused");
            return;
        }
        ClickResult result = runtime.engine().click(session.profile(), Instant.now());
        session.countClick();
        session.touchDirty();
        if (result.rateLimited()) {
            long now = System.currentTimeMillis();
            if (now - session.lastActionbarAt() > 1000) {
                session.lastActionbarAt(now);
                player.sendActionBar(messages.get(player, "cookie.click.rate_limited"));
            }
            telemetry.event("cookie.click_rate_limited", player.getUniqueId(), Map.of());
            return;
        }
        feedback(player, session, result);
        progress.onClick(player.getUniqueId(), result.reward());
        List<String> unlocked = runtime.engine().evaluateAchievements(session.profile());
        for (String achievement : unlocked) {
            messages.send(player, "cookie.achievement.unlocked", Map.of("name", CookieNames.achievement(messages, runtime.engine(), player, achievement)));
            sounds.play(player, "minecraft:ui.toast.challenge_complete", 0.8f, 1.0f);
            telemetry.event("cookie.achievement_unlocked", player.getUniqueId(), Map.of("achievement", achievement));
        }
        if (!unlocked.isEmpty()) {
            progress.onAchievementsUnlocked(player.getUniqueId(), unlocked);
        }
    }

    private void notifyUnavailable(Player player) {
        long now = System.currentTimeMillis();
        Long last = unavailableNoticeAt.get(player.getUniqueId());
        if (last == null || now - last > 5000) {
            unavailableNoticeAt.put(player.getUniqueId(), now);
            messages.send(player, "cookie.unavailable");
            sounds.error(player);
        }
    }

    public void forget(UUID player) {
        unavailableNoticeAt.remove(player);
        lastClickTick.remove(player);
    }

    private void feedback(Player player, CookieSession session, ClickResult result) {
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        boolean effects = tastic == null || tastic.settings().get(LobbySettings.COOKIE_EFFECTS);
        boolean reduced = tastic != null && tastic.settings().get(CoreSettings.REDUCED_EFFECTS);
        boolean actionbar = tastic == null || tastic.settings().get(LobbySettings.COOKIE_ACTIONBAR);
        boolean clickSounds = tastic == null || tastic.settings().get(LobbySettings.COOKIE_CLICK_SOUNDS);
        boolean comboPopups = tastic == null || tastic.settings().get(LobbySettings.COOKIE_COMBO_POPUPS);
        Locale locale = localeOf(player);
        CookieStats stats = runtime.engine().compute(session.profile());
        String combo = result.comboStage() > 0 && comboPopups
                ? messages.raw(messages.languageOf(player), "cookie.click.combo").replace("<combo>", String.format(Locale.ROOT, "%.2f", result.comboMultiplier()))
                : "";
        if (actionbar) {
            session.lastActionbarAt(System.currentTimeMillis());
            player.sendActionBar(messages.get(player, "cookie.click.actionbar", Map.of(
                    "gain", formatter.format(result.reward(), locale),
                    "cookies", formatter.format(session.profile().cookies(), locale),
                    "cps", formatter.formatRate(stats.effectiveCps(), locale),
                    "combo", messages.mini(combo))));
        }
        if (clickSounds) {
            sounds.play(player, "minecraft:entity.item.pickup", 0.4f, result.comboAdvanced() ? 1.6f : 1.2f);
        }
        // animation and particles belong to the cookie the player stands at, not to the first one
        Spot spot = nearestSpot(player);
        String clickSkill = configuration.get().mainCookie().clickSkill();
        if (spot != null && "mythicmobs".equals(spot.backend) && !clickSkill.isBlank()) {
            Entity visual = spot.visual();
            if (visual != null) {
                mobs.castSkill(visual, clickSkill); // hit animation – damage itself is blocked by the lobby
            }
        }
        if (effects && !reduced) {
            Entity visual = spot == null ? null : spot.visual();
            Location at = visual == null ? player.getLocation().add(0, 1.5, 0) : visual.getLocation().add(0, 1.0, 0);
            player.spawnParticle(Particle.ITEM, at, 6, 0.4, 0.4, 0.4, 0.05, new ItemStack(Material.COOKIE));
        }
    }

    /** Proximity actionbar (called from the runtime tick, once per second per session). */
    public void tickActionbar(CookieSession session) {
        Player player = Bukkit.getPlayer(session.player());
        if (player == null) return;
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        long now = System.currentTimeMillis();
        if (now - session.lastActionbarAt() < main.actionbarIntervalMillis()) {
            return;
        }
        if (!main.inZone(player.getLocation())) {
            return;
        }
        boolean hudOn = coreApi.playerManager().find(player.getUniqueId())
                .map(p -> p.settings().get(LobbySettings.COOKIE_HUD) && p.settings().get(LobbySettings.COOKIE_ACTIONBAR)).orElse(true);
        if (!hudOn) return;
        session.lastActionbarAt(now);
        Locale locale = localeOf(player);
        CookieStats stats = runtime.engine().compute(session.profile());
        player.sendActionBar(messages.get(player, "cookie.actionbar.idle", Map.of(
                "cookies", formatter.format(session.profile().cookies(), locale),
                "cps", formatter.formatRate(stats.effectiveCps(), locale),
                "prestige", session.profile().prestigeLevel())));
    }

    private Locale localeOf(Player player) {
        return messages.languageOf(player) == de.tasticgames.localization.SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
    }

    private void reportClicks() {
        for (CookieSession session : runtime.sessions()) {
            int clicks = session.takeClicks();
            if (clicks > 0) {
                telemetry.event("cookie.clicked", session.player(), Map.of("clicks", clicks, "cookies", session.profile().cookies().toPlainString()));
            }
        }
    }
}
