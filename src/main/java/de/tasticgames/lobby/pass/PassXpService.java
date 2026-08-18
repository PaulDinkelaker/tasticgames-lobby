package de.tasticgames.lobby.pass;

import de.tasticgames.lobby.cookie.CookieProgressListener;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.pass.PassXpSource;
import de.tasticgames.pass.PlayerPassService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Turns Cookie Clicker progress into season pass XP and quest metrics. Every hook only accumulates
 * in memory; an async task flushes the accumulated amounts to TasticCore every
 * {@code xp.flush-interval-seconds} (and on quit/shutdown), so a click storm produces one report
 * instead of one call per click – core batches a second time before it reaches the API.
 * <p>
 * Metric names follow the season contract: {@code cookie.clicks}, {@code cookie.cookies_baked},
 * {@code cookie.generators_bought}, {@code cookie.upgrades_bought}, {@code cookie.prestige},
 * {@code cookie.golden}, {@code cookie.zone_discovered}, {@code cookie.npc_quest}.
 */
public final class PassXpService implements Service, CookieProgressListener {

    public static final String METRIC_CLICKS = "cookie.clicks";
    public static final String METRIC_BAKED = "cookie.cookies_baked";
    public static final String METRIC_GENERATORS = "cookie.generators_bought";
    public static final String METRIC_UPGRADES = "cookie.upgrades_bought";
    public static final String METRIC_PRESTIGE = "cookie.prestige";
    public static final String METRIC_GOLDEN = "cookie.golden";
    public static final String METRIC_ZONE = "cookie.zone_discovered";
    public static final String METRIC_NPC_QUEST = "cookie.npc_quest";
    /** Cookie achievement ids are namespaced before they are handed to the pass. */
    public static final String ACHIEVEMENT_PREFIX = "cookie.";

    private final Plugin plugin;
    private final PlayerPassService pass;
    private final PassStateService state;
    private final Supplier<PassConfiguration> configuration;
    private final Logger logger;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public PassXpService(Plugin plugin, PlayerPassService pass, PassStateService state, Supplier<PassConfiguration> configuration, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.pass = Objects.requireNonNull(pass);
        this.state = Objects.requireNonNull(state);
        this.configuration = Objects.requireNonNull(configuration);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "pass-xp-service";
    }

    @Override
    public void start() {
        // the task also runs while the pass is disabled: nothing is accumulated then, and enabling
        // the pass with /passadmin reload starts reporting without a restart
        long period = 20L * configuration.get().xp().flushIntervalSeconds();
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, period, period);
        if (state.enabled()) {
            logger.info("Pass XP hooks active (flush every " + configuration.get().xp().flushIntervalSeconds() + "s, "
                    + configuration.get().xp().clicksPerXp() + " clicks per XP).");
        }
    }

    @Override
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushAll();
        pending.clear();
    }

    /** Flushes and drops what is still accumulated for a leaving player. */
    public void flush(UUID player) {
        Pending accumulated = pending.remove(player);
        if (accumulated != null) {
            report(player, accumulated);
        }
    }

    public int pendingPlayers() {
        return pending.size();
    }

    // ------------------------------------------------------------------ cookie hooks

    @Override
    public void onClick(UUID player, CookieAmount baked) {
        Pending accumulated = accumulate(player);
        if (accumulated == null) {
            return;
        }
        accumulated.click();
        accumulated.metric(METRIC_CLICKS, 1);
        accumulated.metric(METRIC_BAKED, toLong(baked));
    }

    @Override
    public void onGeneratorsBought(UUID player, String generatorId, int count) {
        if (count > 0) {
            record(player, METRIC_GENERATORS, count, PassXpSource.COOKIE_PURCHASE);
        }
    }

    @Override
    public void onUpgradeBought(UUID player, String upgradeId) {
        record(player, METRIC_UPGRADES, 1, PassXpSource.COOKIE_PURCHASE);
    }

    @Override
    public void onPrestige(UUID player, int level) {
        record(player, METRIC_PRESTIGE, 1, PassXpSource.COOKIE_PRESTIGE);
    }

    /**
     * One special cookie is one quest metric, but the XP scales with the rarity
     * (SILVER 1x … MASTER 5x of {@code xp.per-golden}).
     */
    @Override
    public void onSpecialCookie(UUID player, SpecialCookieRarity rarity) {
        Pending accumulated = accumulate(player);
        if (accumulated == null || rarity == null) {
            return;
        }
        accumulated.metric(METRIC_GOLDEN, 1);
        accumulated.xp(PassXpSource.COOKIE_GOLDEN, xpFor(configuration.get().xp(), PassXpSource.COOKIE_GOLDEN, rarity.passXpMultiplier()));
    }

    @Override
    public void onZoneDiscovered(UUID player, String zoneId) {
        record(player, METRIC_ZONE, 1, PassXpSource.COOKIE_ZONE);
    }

    @Override
    public void onNpcQuestCompleted(UUID player, String questId) {
        record(player, METRIC_NPC_QUEST, 1, PassXpSource.QUEST);
    }

    /**
     * Achievements are not batched: the unlock is idempotent per achievement and the API grants its
     * own XP reward for it. The configurable bonus is added to the local batch on top.
     */
    @Override
    public void onAchievementsUnlocked(UUID player, List<String> achievementIds) {
        if (!state.enabled() || achievementIds == null || achievementIds.isEmpty()) {
            return;
        }
        int bonus = configuration.get().xp().perAchievement();
        for (String achievement : achievementIds) {
            if (achievement == null || achievement.isBlank()) {
                continue;
            }
            String key = ACHIEVEMENT_PREFIX + achievement;
            try {
                pass.unlockAchievement(player, key).whenComplete((unlocked, throwable) -> {
                    if (throwable != null) {
                        logger.fine("Pass achievement " + key + " could not be unlocked for " + player + ": " + LobbyThrowables.rootMessage(throwable));
                    }
                });
            } catch (RuntimeException e) {
                logger.warning("Pass achievement " + key + " could not be reported for " + player + ": " + LobbyThrowables.rootMessage(e));
            }
            if (bonus > 0) {
                Pending accumulated = accumulate(player);
                if (accumulated != null) {
                    accumulated.xp(PassXpSource.ACHIEVEMENT, bonus);
                }
            }
        }
    }

    // ------------------------------------------------------------------ batching

    /** Accumulates {@code count} occurrences of a hook: the quest metric plus the configured XP. */
    private void record(UUID player, String metric, long count, PassXpSource source) {
        Pending accumulated = accumulate(player);
        if (accumulated == null) {
            return;
        }
        accumulated.metric(metric, count);
        accumulated.xp(source, xpFor(configuration.get().xp(), source, count));
    }

    /** XP the configured rates award for {@code count} occurrences of a hook. */
    static long xpFor(PassConfiguration.Xp rates, PassXpSource source, long count) {
        int rate = switch (source) {
            case COOKIE_PURCHASE -> rates.perPurchase();
            case COOKIE_PRESTIGE -> rates.perPrestige();
            case COOKIE_GOLDEN -> rates.perGolden();
            case COOKIE_ZONE -> rates.perZone();
            case ACHIEVEMENT -> rates.perAchievement();
            case QUEST -> rates.perNpcQuest();
            default -> 0;
        };
        if (rate <= 0 || count <= 0) {
            return 0;
        }
        return count > Long.MAX_VALUE / rate ? Long.MAX_VALUE : rate * count;
    }

    /** XP earned by accumulated clicks at the configured rate. */
    static long clickXp(long clicks, int clicksPerXp) {
        return clicks <= 0 ? 0 : clicks / Math.max(1, clicksPerXp);
    }

    /** Clicks that did not reach a full XP yet and are carried into the next flush. */
    static long clickRemainder(long clicks, int clicksPerXp) {
        return clicks <= 0 ? 0 : clicks % Math.max(1, clicksPerXp);
    }

    private Pending accumulate(UUID player) {
        if (player == null || !state.enabled()) {
            return null;
        }
        return pending.computeIfAbsent(player, uuid -> new Pending());
    }

    void flushAll() {
        for (UUID player : List.copyOf(pending.keySet())) {
            Pending accumulated = pending.get(player);
            if (accumulated != null) {
                report(player, accumulated);
            }
        }
    }

    /** Hands the accumulated amounts to core and resets the accumulator (the click remainder stays). */
    private void report(UUID player, Pending accumulated) {
        Map<String, Long> metrics;
        Map<PassXpSource, Long> xp;
        long clickXp;
        synchronized (accumulated) {
            int clicksPerXp = configuration.get().xp().clicksPerXp();
            long clicks = accumulated.clicks;
            clickXp = clickXp(clicks, clicksPerXp);
            accumulated.clicks = clickRemainder(clicks, clicksPerXp);
            metrics = accumulated.metrics.isEmpty() ? Map.of() : new LinkedHashMap<>(accumulated.metrics);
            xp = accumulated.xp.isEmpty() ? Map.of() : new EnumMap<>(accumulated.xp);
            accumulated.metrics.clear();
            accumulated.xp.clear();
        }
        try {
            if (clickXp > 0) {
                pass.awardXp(player, PassXpSource.COOKIE_CLICKS, clickXp, "cookie.clicks");
            }
            for (Map.Entry<PassXpSource, Long> entry : xp.entrySet()) {
                if (entry.getValue() > 0) {
                    pass.awardXp(player, entry.getKey(), entry.getValue(), entry.getKey().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            for (Map.Entry<String, Long> entry : metrics.entrySet()) {
                if (entry.getValue() > 0) {
                    pass.metric(player, entry.getKey(), entry.getValue());
                }
            }
        } catch (RuntimeException e) {
            logger.warning("Pass progress of " + player + " could not be reported: " + LobbyThrowables.rootMessage(e));
        }
    }

    /** Cookie amounts grow beyond {@code long}; quest targets do not, so the value is saturated. */
    static long toLong(CookieAmount amount) {
        if (amount == null || amount.isZero()) {
            return 0;
        }
        BigDecimal value = amount.toBigDecimal();
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, value.longValue());
    }

    /** Per player accumulator; {@code clicks} keeps the remainder that did not reach a full XP yet. */
    private static final class Pending {
        private long clicks;
        private final Map<String, Long> metrics = new LinkedHashMap<>();
        private final Map<PassXpSource, Long> xp = new EnumMap<>(PassXpSource.class);

        private synchronized void click() {
            clicks = saturatingAdd(clicks, 1);
        }

        private synchronized void metric(String metric, long amount) {
            if (amount > 0) {
                metrics.merge(metric, amount, PassXpService::saturatingAdd);
            }
        }

        private synchronized void xp(PassXpSource source, long amount) {
            if (amount > 0) {
                xp.merge(source, amount, PassXpService::saturatingAdd);
            }
        }
    }

    static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
