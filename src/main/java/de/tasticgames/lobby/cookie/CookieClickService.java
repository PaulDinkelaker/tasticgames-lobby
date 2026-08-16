package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.ClickResult;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import de.tasticgames.settings.CoreSettings;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
import java.util.logging.Logger;

/**
 * The physical main cookie (Interaction + ItemDisplay + TextDisplay) and click handling with
 * server-side reward validation, combo feedback, sounds/particles honoring settings.
 */
public final class CookieClickService implements Service, Listener {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final CookieConfiguration configuration;
    private final CookieRuntimeService runtime;
    private final CookieWorldService world;
    private final LobbyPlayerService players;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final NamespacedKey markerKey;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();
    private volatile UUID interactionId;
    private volatile UUID displayId;
    private volatile UUID labelId;
    private org.bukkit.scheduler.BukkitTask clickReportTask;

    public CookieClickService(Plugin plugin, TasticCoreApi coreApi, CookieConfiguration configuration, CookieRuntimeService runtime, CookieWorldService world,
                              LobbyPlayerService players, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.world = Objects.requireNonNull(world);
        this.players = Objects.requireNonNull(players);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
        this.markerKey = new NamespacedKey(plugin, "cookie-entity");
    }

    @Override
    public String id() {
        return "cookie-click-service";
    }

    @Override
    public void start() {
        spawnMainCookie();
        clickReportTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reportClicks, 20L * 60, 20L * 60);
    }

    @Override
    public void stop() {
        if (clickReportTask != null) clickReportTask.cancel();
        reportClicks();
        removeMainCookie();
    }

    public NamespacedKey markerKey() {
        return markerKey;
    }

    public CookieNumberFormatter formatter() {
        return formatter;
    }

    /** (Re)spawns the main cookie entities at the configured location; removes stale markers first. */
    public void spawnMainCookie() {
        World w = world.world().orElse(null);
        if (w == null) {
            return;
        }
        removeMainCookie();
        for (Entity entity : w.getEntities()) {
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
        Location location = configuration.world().mainCookie().toLocation(w);
        Interaction interaction = w.spawn(location.clone().subtract(0, 0.5, 0), Interaction.class, e -> {
            e.setInteractionWidth(2.2f);
            e.setInteractionHeight(2.4f);
            e.setResponsive(true);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-interaction");
        });
        ItemDisplay display = w.spawn(location.clone().add(0, 0.7, 0), ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.COOKIE));
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1), new Vector3f(2.0f, 2.0f, 2.0f), new AxisAngle4f(0, 0, 0, 1)));
            e.setPersistent(false);
            e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-display");
        });
        TextDisplay label = w.spawn(location.clone().add(0, 2.4, 0), TextDisplay.class, e -> {
            e.text(Component.text("🍪 ", NamedTextColor.GOLD).append(Component.text("Click me!", NamedTextColor.WHITE)));
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "main-label");
        });
        interactionId = interaction.getUniqueId();
        displayId = display.getUniqueId();
        labelId = label.getUniqueId();
    }

    private void removeMainCookie() {
        for (UUID id : List.of(interactionId, displayId, labelId)) {
            if (id != null) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) entity.remove();
            }
        }
        interactionId = null;
        displayId = null;
        labelId = null;
    }

    private boolean isMainCookie(Entity entity) {
        return entity != null && entity.getUniqueId().equals(interactionId);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (isMainCookie(event.getRightClicked())) {
            event.setCancelled(true);
            click(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        if (isMainCookie(event.getAttacked())) {
            event.setCancelled(true);
            click(event.getPlayer());
        }
    }

    /** Server-side click: validate, reward, feedback. */
    public void click(Player player) {
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            runtime.load(player.getUniqueId());
            return;
        }
        if (session.paused()) {
            messages.send(player, "cookie.paused");
            return;
        }
        if (!world.isCookieWorld(player.getWorld())) {
            return; // interactions only count inside the cookie world
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
            messages.send(player, "cookie.achievement.unlocked", Map.of("name", world.achievementName(player, achievement)));
            sounds.play(player, "minecraft:ui.toast.challenge_complete", 0.8f, 1.0f);
            telemetry.event("cookie.achievement_unlocked", player.getUniqueId(), Map.of("achievement", achievement));
        }
    }

    private void feedback(Player player, CookieSession session, ClickResult result) {
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        boolean effects = tastic == null || tastic.settings().get(LobbySettings.COOKIE_EFFECTS);
        boolean reduced = tastic != null && tastic.settings().get(CoreSettings.REDUCED_EFFECTS);
        Locale locale = messages.languageOf(player) == de.tasticgames.localization.SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
        CookieStats stats = runtime.engine().compute(session.profile());
        String combo = result.comboStage() > 0 ? messages.raw(messages.languageOf(player), "cookie.click.combo").replace("<combo>",
                String.format(Locale.ROOT, "%.2f", result.comboMultiplier())) : "";
        player.sendActionBar(messages.get(player, "cookie.click.actionbar", Map.of(
                "gain", formatter.format(result.reward(), locale),
                "cookies", formatter.format(session.profile().cookies(), locale),
                "cps", formatter.formatRate(stats.effectiveCps(), locale),
                "combo", messages.mini(combo))));
        sounds.play(player, "minecraft:entity.item.pickup", 0.4f, result.comboAdvanced() ? 1.6f : 1.2f);
        if (effects && !reduced) {
            Entity display = displayId == null ? null : Bukkit.getEntity(displayId);
            Location at = display == null ? player.getLocation().add(0, 1.5, 0) : display.getLocation();
            player.spawnParticle(Particle.ITEM, at, 6, 0.4, 0.4, 0.4, 0.05, new ItemStack(Material.COOKIE));
        }
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
