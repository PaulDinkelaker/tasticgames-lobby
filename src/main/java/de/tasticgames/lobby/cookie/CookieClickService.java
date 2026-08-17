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
    private volatile String backend = "none";
    private volatile UUID interactionId;
    private volatile UUID visualId;
    private volatile UUID labelId;
    private volatile Chunk ticketChunk;
    private org.bukkit.scheduler.BukkitTask clickReportTask;
    private org.bukkit.scheduler.BukkitTask healTask;
    private long lastLoadWarningAt;
    private volatile boolean modelWarningShown;

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
            if (base.equals(visualId)) {
                handleClick(player, left);
                return true;
            }
            return false;
        });
        spawnMainCookie();
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

    public NamespacedKey markerKey() {
        return markerKey;
    }

    public CookieNumberFormatter formatter() {
        return formatter;
    }

    /** Active visual backend: mythicmobs, modelengine, native or none. */
    public String backend() {
        return backend;
    }

    public boolean spawned() {
        return interactionId != null && Bukkit.getEntity(interactionId) != null;
    }

    public Location mainCookieLocation() {
        CookieConfiguration.Point point = configuration.get().mainCookie().location();
        World world = Bukkit.getWorld(point.world());
        return world == null ? null : point.toLocation(world);
    }

    /** (Re)spawns the main cookie entities at the configured location; removes stale markers first. */
    public synchronized void spawnMainCookie() {
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        World w = Bukkit.getWorld(main.world());
        if (w == null) {
            long now = System.currentTimeMillis();
            if (now - lastLoadWarningAt > 60_000) {
                lastLoadWarningAt = now;
                logger.warning("Main cookie world '" + main.world() + "' is not loaded – the main cookie spawns as soon as the world is available.");
            }
            return;
        }
        removeMainCookie();
        modelWarningShown = false;
        Location location = main.location().toLocation(w);
        Chunk chunk = w.getChunkAt(location);
        chunk.load();
        chunk.addPluginChunkTicket(plugin);
        ticketChunk = chunk;
        for (Entity entity : w.getNearbyEntities(location, 6, 6, 6)) {
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) {
                entity.remove();
            } else if (!main.mythicMobsType().isBlank() && mobs.available()
                    && mobs.mobType(entity).map(t -> t.equalsIgnoreCase(main.mythicMobsType())).orElse(false)) {
                // a manually spawned (persistent) copy of the cookie mob – the plugin owns the main cookie
                logger.info("Removing manually spawned MythicMobs '" + main.mythicMobsType() + "' next to the main cookie.");
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
                logger.warning("MythicMobs type '" + main.mythicMobsType() + "' for the main cookie does not exist – falling back.");
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
                logger.warning("ModelEngine model '" + main.model() + "' for the main cookie does not exist – falling back to the native display.");
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
        visualId = visual.getUniqueId();
        backend = usedBackend;
        if (!"native".equals(usedBackend)) {
            // MythicMobs applies its model a tick after spawning: hide the base entity (e.g. the pig) now and again shortly after
            hideBase(visual);
            mainThread.later(2L, () -> hideBase(visualId == null ? null : Bukkit.getEntity(visualId)));
            mainThread.later(20L, () -> hideBase(visualId == null ? null : Bukkit.getEntity(visualId)));
        }

        Interaction interaction = w.spawn(location.clone(), Interaction.class, e -> {
            e.setInteractionWidth((float) main.hitboxWidth());
            e.setInteractionHeight((float) main.hitboxHeight());
            e.setResponsive(true);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-interaction");
        });
        interactionId = interaction.getUniqueId();

        if (main.label()) {
            TextDisplay label = w.spawn(location.clone().add(0, main.hitboxHeight() + 0.3, 0), TextDisplay.class, e -> {
                e.text(Component.text("🍪 ", NamedTextColor.GOLD).append(Component.text("Cookie Clicker", NamedTextColor.WHITE))
                        .append(Component.newline()).append(Component.text("Click me!", NamedTextColor.GRAY)));
                e.setBillboard(Display.Billboard.CENTER);
                e.setSeeThrough(false);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-label");
            });
            labelId = label.getUniqueId();
        }
        logger.info("Main cookie spawned in " + w.getName() + " at " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                + " (backend " + usedBackend + ").");
    }

    private synchronized void removeMainCookie() {
        Entity visual = visualId == null ? null : Bukkit.getEntity(visualId);
        if (visual != null) {
            try {
                if ("modelengine".equals(backend)) {
                    models.detach(visual);
                } else if ("mythicmobs".equals(backend)) {
                    mobs.remove(visual);
                }
            } catch (RuntimeException e) {
                logger.warning("Main cookie visual could not be removed cleanly: " + e.getMessage());
            }
            if (visual.isValid()) {
                visual.remove();
            }
        }
        for (UUID id : new UUID[]{interactionId, labelId}) {
            if (id == null) continue;
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        if (ticketChunk != null) {
            ticketChunk.removePluginChunkTicket(plugin);
            ticketChunk = null;
        }
        interactionId = null;
        visualId = null;
        labelId = null;
        backend = "none";
    }

    /**
     * The visual base entity (MythicMobs mob / ModelEngine base) must never be visible: the model
     * plugin hides it when it knows the entity, and the entity itself is made invisible as a
     * belt-and-braces measure (the ModelEngine model is rendered separately).
     */
    private void hideBase(Entity visual) {
        if (visual == null || !visual.isValid() || "native".equals(backend)) {
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
        if (!hidden && models.available() && !modeled && "mythicmobs".equals(backend) && !modelWarningShown) {
            modelWarningShown = true;
            logger.info("MythicMobs mob '" + configuration.get().mainCookie().mythicMobsType()
                    + "' is not registered as ModelEngine entity (yet) – base entity kept invisible; ignore this if the model renders.");
        }
    }

    /** Self-heal: respawn when an entity vanished (chunk unload, /kill @e, world reload). */
    private void heal() {
        World w = Bukkit.getWorld(configuration.get().mainCookie().world());
        if (w == null) {
            return;
        }
        boolean interactionAlive = interactionId != null && Bukkit.getEntity(interactionId) != null;
        Entity visual = visualId == null ? null : Bukkit.getEntity(visualId);
        if (!interactionAlive || visual == null) {
            logger.info("Main cookie entities missing – respawning.");
            spawnMainCookie();
            return;
        }
        hideBase(visual);
    }

    /** Whether the player stands inside the cookie zone around the main cookie. */
    public boolean inZone(Player player) {
        return player != null && configuration.get().mainCookie().inZone(player.getLocation());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getName().equals(configuration.get().mainCookie().world()) && !spawned()) {
            mainThread.later(1L, this::spawnMainCookie);
        }
    }

    private boolean isMainCookie(Entity entity) {
        if (entity == null) return false;
        UUID id = entity.getUniqueId();
        return id.equals(interactionId) || id.equals(visualId) || id.equals(labelId);
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
        Location cookie = mainCookieLocation();
        if (cookie == null || !player.getWorld().equals(cookie.getWorld())) return;
        CookieConfiguration.MainCookie main = configuration.get().mainCookie();
        double reach = 5.0;
        if (player.getEyeLocation().distanceSquared(cookie) > (reach + main.hitboxHeight()) * (reach + main.hitboxHeight())) return;
        double half = main.hitboxWidth() / 2.0;
        BoundingBox box = new BoundingBox(cookie.getX() - half, cookie.getY(), cookie.getZ() - half,
                cookie.getX() + half, cookie.getY() + main.hitboxHeight(), cookie.getZ() + half);
        RayTraceResult hit = box.rayTrace(player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), reach);
        if (hit == null) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
        }
        handleClick(player, true);
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
        List<String> unlocked = runtime.engine().evaluateAchievements(session.profile());
        for (String achievement : unlocked) {
            messages.send(player, "cookie.achievement.unlocked", Map.of("name", CookieNames.achievement(messages, runtime.engine(), player, achievement)));
            sounds.play(player, "minecraft:ui.toast.challenge_complete", 0.8f, 1.0f);
            telemetry.event("cookie.achievement_unlocked", player.getUniqueId(), Map.of("achievement", achievement));
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
        Locale locale = localeOf(player);
        CookieStats stats = runtime.engine().compute(session.profile());
        String combo = result.comboStage() > 0
                ? messages.raw(messages.languageOf(player), "cookie.click.combo").replace("<combo>", String.format(Locale.ROOT, "%.2f", result.comboMultiplier()))
                : "";
        session.lastActionbarAt(System.currentTimeMillis());
        player.sendActionBar(messages.get(player, "cookie.click.actionbar", Map.of(
                "gain", formatter.format(result.reward(), locale),
                "cookies", formatter.format(session.profile().cookies(), locale),
                "cps", formatter.formatRate(stats.effectiveCps(), locale),
                "combo", messages.mini(combo))));
        sounds.play(player, "minecraft:entity.item.pickup", 0.4f, result.comboAdvanced() ? 1.6f : 1.2f);
        String clickSkill = configuration.get().mainCookie().clickSkill();
        if ("mythicmobs".equals(backend) && !clickSkill.isBlank() && visualId != null) {
            Entity visual = Bukkit.getEntity(visualId);
            if (visual != null) {
                mobs.castSkill(visual, clickSkill); // hit animation – damage itself is blocked by the lobby
            }
        }
        if (effects && !reduced) {
            Entity visual = visualId == null ? null : Bukkit.getEntity(visualId);
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
        boolean hudOn = coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.COOKIE_HUD)).orElse(true);
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
