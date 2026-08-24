package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieReward;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRoll;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Player-specific special cookies (SILVER … MASTER). Every session draws its own next-spawn instant
 * ({@code 15–120 min / chance multiplier}, never below the floor); when it is due and the player
 * stands in a special cookie area (lobby areas around the main cookie, or the open world) a rarity
 * is drawn from the rarities the player's prestige level unlocked. Mode {@code auto} (default)
 * activates it right away (title, sound, particles, reward); mode {@code spawn} places a
 * rarity-coloured cookie near the player (visible only to them, limited lifetime,
 * ownership-validated click, cleanup on quit/exit).
 * <p>
 * The timer is intentionally in memory only: reconnecting redraws a full interval, so it can never
 * produce a cookie sooner than the configured floor and never shortens an already running wait.
 */
public final class SpecialCookieService implements Service, Listener {

    private record Special(UUID owner, SpecialCookieRarity rarity, UUID interaction, UUID display, UUID label, Instant expiresAt) {
    }

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final Supplier<CookieConfiguration> configuration;
    private final CookieRuntimeService runtime;
    private final CookieWorldService world;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();
    private final Map<UUID, Special> active = new ConcurrentHashMap<>();
    /** How long a running wait is kept after a quit before it is dropped (longest interval plus slack). */
    private static final long TIMER_RETENTION_SECONDS = 6L * 60L * 60L;

    private final Map<UUID, Instant> nextSpecial = new ConcurrentHashMap<>();
    private volatile CookieProgressListener progress = CookieProgressListener.NONE;
    private org.bukkit.scheduler.BukkitTask task;

    public SpecialCookieService(Plugin plugin, TasticCoreApi coreApi, Supplier<CookieConfiguration> configuration, CookieRuntimeService runtime,
                                CookieWorldService world, LobbyMessages messages, LobbySounds sounds, LobbyTelemetryService telemetry) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configuration = Objects.requireNonNull(configuration);
        this.runtime = Objects.requireNonNull(runtime);
        this.world = Objects.requireNonNull(world);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    /** Consumer of special cookie progress (season pass XP/metrics); {@link CookieProgressListener#NONE} by default. */
    public void setProgressListener(CookieProgressListener listener) {
        this.progress = Objects.requireNonNull(listener);
    }

    @Override
    public String id() {
        return "special-cookie-service";
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
        nextSpecial.clear();
    }

    public int activeCount() {
        return active.size();
    }

    /** Instant the player's next special cookie is due (empty while no session timer runs). */
    public Optional<Instant> nextSpecialAt(UUID player) {
        return Optional.ofNullable(nextSpecial.get(player));
    }

    /** Whether the special cookie activates directly (auto) instead of appearing in the world (spawn) where the player stands. */
    boolean autoMode(Player player) {
        CookieConfiguration config = configuration.get();
        if (world.isOpenWorld(player.getWorld())) {
            return config.openWorld().specialAuto();
        }
        return config.mainCookie().specialAuto();
    }

    /** Whether the player currently stands where special cookies may appear. */
    boolean eligible(Player player) {
        CookieConfiguration config = configuration.get();
        Location location = player.getLocation();
        String worldName = player.getWorld().getName();
        if (config.mainCookie().specialEnabled() && worldName.equals(config.mainCookie().world())) {
            // Special cookies belong to the cookie, not to the whole lobby: they follow the same
            // zone radius as the generators. Whoever stands at the fountain gets none - by design.
            if (!config.mainCookie().inZone(location)) {
                return false;
            }
            List<LobbyConfiguration.Region> areas = config.mainCookie().specialAreas();
            return areas.isEmpty() || areas.stream().anyMatch(r -> r.contains(location));
        }
        if (config.openWorld().specialEnabled() && world.isOpenWorld(player.getWorld())) {
            List<LobbyConfiguration.Region> areas = config.openWorld().specialAreas();
            return areas.isEmpty() || areas.stream().anyMatch(r -> r.contains(location));
        }
        return false;
    }

    private void tick() {
        Instant now = Instant.now();
        for (Special special : List.copyOf(active.values())) {
            if (!special.expiresAt().isAfter(now)) {
                remove(special.owner());
            }
        }
        // waits of players who never came back: drop them well after the longest possible interval
        Instant stale = now.minusSeconds(TIMER_RETENTION_SECONDS);
        nextSpecial.entrySet().removeIf(e -> e.getValue().isBefore(stale) && Bukkit.getPlayer(e.getKey()) == null);
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (active.containsKey(uuid)) {
                continue;
            }
            CookieSession session = runtime.session(uuid).orElse(null);
            if (session == null || session.paused()) {
                continue;
            }
            Instant due = nextSpecial.get(uuid);
            if (due == null) {
                reschedule(session, now); // first tick of the session: start the wait
                continue;
            }
            if (due.isAfter(now)) {
                continue;
            }
            if (!eligible(player)) {
                // the wait is kept: rerolling here would exclude everyone who is not permanently parked
                // in the cookie area (and every player with short sessions) from the feature entirely
                continue;
            }
            SpecialCookieRoll roll = runtime.engine().rollSpecial(session.profile(), ThreadLocalRandom.current(), now).orElse(null);
            reschedule(session, now);
            if (roll == null) {
                continue; // prestige 0: special cookies are a prestige-1 unlock
            }
            if (autoMode(player)) {
                activate(player, session, roll.rarity(), "auto");
            } else if (!spawn(player, roll)) {
                // nowhere to place it (roof, cramped build): hand it over directly instead of losing the roll
                activate(player, session, roll.rarity(), "spawn-fallback");
            }
        }
    }

    private void reschedule(CookieSession session, Instant now) {
        nextSpecial.put(session.player(), runtime.engine().scheduleNextSpecial(session.profile(), ThreadLocalRandom.current(), now));
    }

    /** Auto mode: the special cookie is activated for the player immediately – no entity to find or click. */
    private void activate(Player player, CookieSession session, SpecialCookieRarity rarity, String trigger) {
        Instant now = Instant.now();
        SpecialCookieReward reward = runtime.engine().rewardFor(session.profile(), rarity, ThreadLocalRandom.current(), now);
        runtime.engine().applySpecialReward(session.profile(), reward, now);
        session.touchDirty();
        Component name = messages.get(player, rarity.nameKey());
        Component rewardLine = messages.get(player, rewardKey(reward), rewardPlaceholders(reward, now));
        if (alertsEnabled(player)) {
            player.showTitle(Title.title(messages.get(player, "cookie.special.title", Map.of("rarity", name)), rewardLine,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(600))));
            sounds.play(player, "minecraft:block.amethyst_block.chime", 1.0f, 1.3f);
            sounds.play(player, "minecraft:entity.player.levelup", 0.9f, 1.4f);
        }
        // the reward line itself always goes to chat: a buff the player cannot see is worse than a line too many
        messages.send(player, "cookie.special.activated", Map.of("rarity", name));
        player.sendMessage(rewardLine);
        if (effectsEnabled(player)) {
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.25);
        }
        telemetry.event("cookie.golden_activated", player.getUniqueId(),
                Map.of("rarity", rarity, "type", reward.type(), "trigger", trigger));
        progress.onSpecialCookie(player.getUniqueId(), rarity);
        notifyAchievements(player, session);
    }

    /** Titles and fanfare for a special cookie ({@link LobbySettings#COOKIE_SPECIAL_ALERTS}). */
    private boolean alertsEnabled(Player player) {
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        return tastic == null || tastic.settings().get(LobbySettings.COOKIE_SPECIAL_ALERTS);
    }

    private boolean effectsEnabled(Player player) {
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        return tastic == null || tastic.settings().get(LobbySettings.COOKIE_EFFECTS);
    }

    /** Announces newly unlocked achievements and forwards them to the progress listener. */
    private void notifyAchievements(Player player, CookieSession session) {
        List<String> unlocked = runtime.engine().evaluateAchievements(session.profile());
        if (unlocked.isEmpty()) {
            return;
        }
        unlocked.forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", CookieNames.achievement(messages, runtime.engine(), player, a))));
        progress.onAchievementsUnlocked(player.getUniqueId(), unlocked);
    }

    private static String rewardKey(SpecialCookieReward reward) {
        return switch (reward.type()) {
            case LUCKY -> "cookie.special.lucky";
            case FRENZY -> "cookie.special.frenzy";
            case CLICK_FRENZY -> "cookie.special.click_frenzy";
            case CHAIN_BONUS -> "cookie.special.chain_bonus";
            case BLESSING -> "cookie.special.blessing";
        };
    }

    /** Placeholders every reward line may use: cookies, remaining seconds and the buff multipliers. */
    private Map<String, Object> rewardPlaceholders(SpecialCookieReward reward, Instant now) {
        long seconds = reward.buffExpiresAt().map(expiry -> Duration.between(now, expiry).getSeconds()).orElse(0L);
        double leading = reward.type() == GoldenRewardType.CLICK_FRENZY ? reward.clickMultiplier() : reward.cpsMultiplier();
        return Map.of("cookies", formatter.format(reward.cookies()),
                "seconds", seconds,
                "multiplier", multiplier(leading),
                "click_multiplier", multiplier(reward.clickMultiplier()));
    }

    private static String multiplier(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** Item shown for a spawned cookie – the closest vanilla stand-in for the rarity. */
    private static Material material(SpecialCookieRarity rarity) {
        return switch (rarity) {
            case SILVER -> Material.IRON_NUGGET;
            case GOLDEN -> Material.GOLD_NUGGET;
            case PLATINUM -> Material.PRISMARINE_CRYSTALS;
            case DIAMOND -> Material.DIAMOND;
            case MASTER -> Material.NETHER_STAR;
        };
    }

    /** Places the cookie near the player; {@code false} when no free spot was found. */
    private boolean spawn(Player player, SpecialCookieRoll roll) {
        Location base = player.getLocation();
        Location target = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double distance = 3 + ThreadLocalRandom.current().nextDouble(5);
            Location candidate = base.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            int highest = candidate.getWorld().getHighestBlockYAt(candidate);
            candidate.setY(highest > candidate.getWorld().getMinHeight() ? highest + 1.5 : base.getY() + 1.0);
            if (Math.abs(candidate.getY() - base.getY()) < 6 && candidate.getBlock().getType().isAir()) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        SpecialCookieRarity rarity = roll.rarity();
        Component name = messages.get(player, rarity.nameKey());
        World w = target.getWorld();
        Location spot = target;
        Interaction interaction = w.spawn(spot.clone().subtract(0, 0.4, 0), Interaction.class, e -> {
            e.setInteractionWidth(1.2f);
            e.setInteractionHeight(1.4f);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        ItemDisplay display = w.spawn(spot.clone().add(0, 0.4, 0), ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(material(rarity)));
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setGlowing(true);
            e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f(0, 0, 0, 1)));
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        TextDisplay label = w.spawn(spot.clone().add(0, 1.4, 0), TextDisplay.class, e -> {
            e.text(Component.text("✦ ", TextColor.fromHexString(rarity.color())).append(name)
                    .append(Component.text(" ✦", TextColor.fromHexString(rarity.color()))));
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        for (Entity entity : List.of(interaction, display, label)) {
            player.showEntity(plugin, entity);
        }
        active.put(player.getUniqueId(), new Special(player.getUniqueId(), rarity, interaction.getUniqueId(),
                display.getUniqueId(), label.getUniqueId(), roll.expiresAt()));
        messages.send(player, "cookie.special.spawned", Map.of("rarity", name));
        if (alertsEnabled(player)) {
            sounds.play(player, "minecraft:block.amethyst_block.chime", 1.0f, 1.3f);
        }
        telemetry.event("cookie.golden_spawned", player.getUniqueId(), Map.of("rarity", rarity));
        return true;
    }

    /** Removes a spawned cookie (world exit, expiry, quit) without touching the player's timer. */
    /**
     * Activates a special cookie for the player right away (daily rewards, admin tools).
     *
     * @return false when the rarity is not unlocked at the player's prestige level or no session is loaded
     */
    public boolean grant(Player player, SpecialCookieRarity rarity) {
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null || rarity == null || session.profile().prestigeLevel() < rarity.unlockPrestige()) {
            return false;
        }
        activate(player, session, rarity, "granted");
        return true;
    }

    public void remove(UUID owner) {
        Special special = active.remove(owner);
        if (special == null) return;
        for (UUID id : List.of(special.interaction(), special.display(), special.label())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    /**
     * Drops the player's spawned cookie on quit but KEEPS the running wait: it continues in real time, so
     * short sessions add up instead of restarting the timer on every login. Stale entries of players who did
     * not come back are cleaned up in {@link #tick()}.
     */
    public void forget(UUID owner) {
        remove(owner);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        handle(event.getPlayer(), event.getRightClicked(), event::setCancelled);
    }

    @EventHandler // pre-cancelled for Interaction entities – must not use ignoreCancelled
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        handle(event.getPlayer(), event.getAttacked(), event::setCancelled);
    }

    private void handle(Player player, Entity entity, java.util.function.Consumer<Boolean> cancel) {
        Special special = active.get(player.getUniqueId());
        if (special == null || !special.interaction().equals(entity.getUniqueId())) {
            for (Special other : active.values()) {
                if (other.interaction().equals(entity.getUniqueId())) {
                    cancel.accept(true); // someone else's special cookie: ownership validation
                    return;
                }
            }
            return;
        }
        cancel.accept(true);
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        Instant now = Instant.now();
        SpecialCookieRarity rarity = special.rarity();
        SpecialCookieReward reward = runtime.engine().rewardFor(session.profile(), rarity, ThreadLocalRandom.current(), now);
        runtime.engine().applySpecialReward(session.profile(), reward, now);
        session.touchDirty();
        messages.send(player, rewardKey(reward), rewardPlaceholders(reward, now));
        sounds.play(player, "minecraft:entity.player.levelup", 0.9f, 1.4f);
        if (effectsEnabled(player)) {
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, entity.getLocation(), 30, 0.5, 0.5, 0.5, 0.2);
        }
        telemetry.event("cookie.golden_clicked", player.getUniqueId(), Map.of("rarity", rarity, "type", reward.type()));
        progress.onSpecialCookie(player.getUniqueId(), rarity);
        notifyAchievements(player, session);
    }
}
