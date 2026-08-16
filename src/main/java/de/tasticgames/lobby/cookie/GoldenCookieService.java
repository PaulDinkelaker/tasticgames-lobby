package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.cookie.domain.model.GoldenCookieReward;
import de.tasticgames.lobby.cookie.domain.model.GoldenCookieRoll;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Player-specific golden cookies: rolled per second by the engine, spawned near the player
 * (visible only to them), limited lifetime, ownership-validated click, cleanup on quit/exit.
 */
public final class GoldenCookieService implements Service, Listener {

    private record Golden(UUID owner, UUID interaction, UUID display, UUID label, Instant expiresAt) {
    }

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
    private final Map<UUID, Golden> active = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastRoll = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask task;

    public GoldenCookieService(Plugin plugin, TasticCoreApi coreApi, CookieConfiguration configuration, CookieRuntimeService runtime, CookieWorldService world,
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
    }

    @Override
    public String id() {
        return "golden-cookie-service";
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @Override
    public void stop() {
        if (task != null) task.cancel();
        for (UUID owner : List.copyOf(active.keySet())) {
            remove(owner);
        }
    }

    public int activeCount() {
        return active.size();
    }

    private void tick() {
        if (!configuration.runtime().goldenEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (Golden golden : List.copyOf(active.values())) {
            if (!golden.expiresAt().isAfter(now)) {
                remove(golden.owner());
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!world.isCookieWorld(player.getWorld()) || active.containsKey(player.getUniqueId())) {
                continue;
            }
            CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
            if (session == null || session.paused()) {
                continue;
            }
            Instant previous = lastRoll.put(player.getUniqueId(), now);
            double elapsed = previous == null ? 1.0 : Math.max(0.5, (now.toEpochMilli() - previous.toEpochMilli()) / 1000.0);
            GoldenCookieRoll roll = runtime.engine().roll(session.profile(), ThreadLocalRandom.current(), now, elapsed);
            if (roll.spawned()) {
                spawn(player, roll.expiresAt());
            }
        }
    }

    private void spawn(Player player, Instant expiresAt) {
        Location base = player.getLocation();
        Location target = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double distance = 4 + ThreadLocalRandom.current().nextDouble(6);
            Location candidate = base.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            candidate.setY(candidate.getWorld().getHighestBlockYAt(candidate) + 1.5);
            boolean allowed = configuration.goldenAreas().isEmpty() || configuration.goldenAreas().stream().anyMatch(r -> r.contains(candidate));
            if (allowed && Math.abs(candidate.getY() - base.getY()) < 8) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return;
        }
        World w = target.getWorld();
        Location spot = target;
        Interaction interaction = w.spawn(spot.clone().subtract(0, 0.4, 0), Interaction.class, e -> {
            e.setInteractionWidth(1.2f);
            e.setInteractionHeight(1.4f);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        ItemDisplay display = w.spawn(spot.clone().add(0, 0.4, 0), ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.GOLD_NUGGET));
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setGlowing(true);
            e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f(0, 0, 0, 1)));
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        TextDisplay label = w.spawn(spot.clone().add(0, 1.4, 0), TextDisplay.class, e -> {
            e.text(Component.text("✦ Golden Cookie ✦", NamedTextColor.GOLD));
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        for (Entity entity : List.of(interaction, display, label)) {
            player.showEntity(plugin, entity);
        }
        active.put(player.getUniqueId(), new Golden(player.getUniqueId(), interaction.getUniqueId(), display.getUniqueId(), label.getUniqueId(), expiresAt));
        messages.send(player, "cookie.golden.spawned");
        sounds.play(player, "minecraft:block.amethyst_block.chime", 1.0f, 1.3f);
        telemetry.event("cookie.golden_spawned", player.getUniqueId(), Map.of());
    }

    public void remove(UUID owner) {
        Golden golden = active.remove(owner);
        if (golden == null) return;
        for (UUID id : List.of(golden.interaction(), golden.display(), golden.label())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        handle(event.getPlayer(), event.getRightClicked(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        handle(event.getPlayer(), event.getAttacked(), event::setCancelled);
    }

    private void handle(Player player, Entity entity, java.util.function.Consumer<Boolean> cancel) {
        Golden golden = active.get(player.getUniqueId());
        if (golden == null || !golden.interaction().equals(entity.getUniqueId())) {
            // clicking someone else's golden cookie does nothing (ownership validation)
            for (Golden other : active.values()) {
                if (other.interaction().equals(entity.getUniqueId())) {
                    cancel.accept(true);
                    return;
                }
            }
            return;
        }
        cancel.accept(true);
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            remove(player.getUniqueId());
            return;
        }
        remove(player.getUniqueId());
        Instant now = Instant.now();
        GoldenCookieReward reward = runtime.engine().rewardFor(session.profile(), ThreadLocalRandom.current(), now);
        runtime.engine().applyGoldenReward(session.profile(), reward, now);
        session.touchDirty();
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        boolean effects = tastic == null || tastic.settings().get(LobbySettings.COOKIE_EFFECTS);
        String key = switch (reward.type()) {
            case LUCKY -> "cookie.golden.lucky";
            case FRENZY -> "cookie.golden.frenzy";
            case CLICK_FRENZY -> "cookie.golden.click_frenzy";
            case CHAIN_BONUS -> "cookie.golden.chain_bonus";
        };
        long seconds = reward.buff() == null ? 0 : java.time.Duration.between(now, reward.buff().expiresAt()).getSeconds();
        messages.send(player, key, Map.of("cookies", new de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter().format(reward.cookies()), "seconds", seconds));
        sounds.play(player, "minecraft:entity.player.levelup", 0.9f, 1.4f);
        if (effects) {
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, entity.getLocation(), 30, 0.5, 0.5, 0.5, 0.2);
        }
        telemetry.event("cookie.golden_clicked", player.getUniqueId(), Map.of("type", reward.type()));
        runtime.engine().evaluateAchievements(session.profile()).forEach(a ->
                messages.send(player, "cookie.achievement.unlocked", Map.of("name", world.achievementName(player, a))));
    }
}
