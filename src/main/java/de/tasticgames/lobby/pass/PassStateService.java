package de.tasticgames.lobby.pass;

import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.pass.PassLeaderboardEntry;
import de.tasticgames.pass.PassQuestSnapshot;
import de.tasticgames.pass.PassRewardGrantResult;
import de.tasticgames.pass.PassSeasonSnapshot;
import de.tasticgames.pass.PassSnapshot;
import de.tasticgames.pass.PassTierSnapshot;
import de.tasticgames.pass.PassTrack;
import de.tasticgames.pass.PlayerPassService;
import de.tasticgames.service.Service;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Thin cache/refresh layer over TasticCore's {@link PlayerPassService}: core owns the snapshots,
 * the batching and the API traffic, the lobby only remembers when a snapshot was last fetched so a
 * dialog can show fresh numbers without hammering the API. Every method degrades to an empty result
 * when the pass is disabled or the API is unavailable – nothing here ever throws.
 */
public final class PassStateService implements Service {

    /** A cached snapshot older than this is re-fetched before a dialog renders it. */
    static final Duration STALE_AFTER = Duration.ofSeconds(30);

    private final PlayerPassService pass;
    private final Supplier<PassConfiguration> configuration;
    private final Logger logger;
    private final Map<UUID, Instant> fetchedAt = new ConcurrentHashMap<>();

    public PassStateService(PlayerPassService pass, Supplier<PassConfiguration> configuration, Logger logger) {
        this.pass = Objects.requireNonNull(pass);
        this.configuration = Objects.requireNonNull(configuration);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "pass-state-service";
    }

    @Override
    public void start() {
        if (!enabled()) {
            logger.info("TasticPass is disabled in config/pass.yml – no pass XP is reported and the pass dialogs stay closed.");
            return;
        }
        logger.info("TasticPass state service started (season: " + season().map(PassSeasonSnapshot::key).orElse("not available yet") + ").");
    }

    @Override
    public void stop() {
        fetchedAt.clear();
    }

    public boolean enabled() {
        return configuration.get().enabled();
    }

    /** Whether the pass can be used right now (enabled, API reachable and a season is ACTIVE). */
    public boolean available() {
        return enabled() && pass.season().isPresent();
    }

    public Optional<PassSeasonSnapshot> season() {
        return enabled() ? pass.season() : Optional.empty();
    }

    public Optional<PassSnapshot> state(UUID player) {
        return enabled() ? pass.state(player).filter(PassSnapshot::seasonActive) : Optional.empty();
    }

    /** Loads the pass state after the lobby init pipeline finished (silent, never blocks the join). */
    public void preload(Player player) {
        if (!enabled()) {
            return;
        }
        load(player.getUniqueId()).whenComplete((snapshot, throwable) -> {
            if (throwable != null) {
                logger.warning("Pass state preload failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
            }
        });
    }

    /** Cached snapshot, loading it once when the player has none yet. */
    public CompletableFuture<PassSnapshot> load(UUID player) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(PassSnapshot.inactive());
        }
        return pass.load(player).whenComplete((snapshot, throwable) -> fetchedAt.put(player, Instant.now()));
    }

    /**
     * Forces a fresh snapshot. Core answers {@link PlayerPassService#load(UUID)} from its cache, so
     * the state is dropped first – that also flushes the pending XP/metric batch of the player, and
     * the reload starts in the same statement, so no report is lost in between.
     */
    public CompletableFuture<PassSnapshot> refresh(UUID player) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(PassSnapshot.inactive());
        }
        pass.unload(player);
        return load(player);
    }

    /** Cached snapshot, refreshed when it is older than {@link #STALE_AFTER}. */
    public CompletableFuture<PassSnapshot> fresh(UUID player) {
        Instant last = fetchedAt.get(player);
        PassSnapshot cached = state(player).orElse(null);
        if (cached != null && last != null && Duration.between(last, Instant.now()).compareTo(STALE_AFTER) < 0) {
            return CompletableFuture.completedFuture(cached);
        }
        return refresh(player);
    }

    /** Forgets the local bookkeeping on quit; core unloads its own state through its runtime listener. */
    public void forget(UUID player) {
        fetchedAt.remove(player);
    }

    public CompletableFuture<PassRewardGrantResult> claim(UUID player, int level, PassTrack track) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(PassRewardGrantResult.unavailable());
        }
        return pass.claim(player, level, track).whenComplete((result, throwable) -> fetchedAt.put(player, Instant.now()));
    }

    public CompletableFuture<PassRewardGrantResult> claimAll(UUID player) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(PassRewardGrantResult.unavailable());
        }
        return pass.claimAll(player).whenComplete((result, throwable) -> fetchedAt.put(player, Instant.now()));
    }

    public CompletableFuture<List<PassLeaderboardEntry>> leaderboard() {
        if (!enabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return pass.leaderboard(configuration.get().leaderboardSize());
    }

    public boolean premium(UUID player) {
        return enabled() && pass.premium(player);
    }

    public int level(UUID player) {
        return enabled() ? pass.level(player) : 0;
    }

    public int loadedCount() {
        return enabled() ? pass.loadedCount() : 0;
    }

    // ------------------------------------------------------------------ derived views

    /** Both tiers of a level, ordered FREE before PREMIUM. */
    public List<PassTierSnapshot> tiers(int level) {
        return season().map(season -> season.tiers().stream()
                        .filter(tier -> tier.level() == level)
                        .sorted(Comparator.comparing(PassTierSnapshot::track))
                        .toList())
                .orElse(List.of());
    }

    public Optional<PassTierSnapshot> tier(int level, PassTrack track) {
        return tiers(level).stream().filter(tier -> tier.track() == track).findFirst();
    }

    public static boolean claimed(PassSnapshot snapshot, int level, PassTrack track) {
        return snapshot != null && snapshot.claims().stream().anyMatch(claim -> claim.level() == level && claim.track() == track);
    }

    /** Whether the player may claim this tier right now (reached, not claimed, premium when required). */
    public boolean claimable(PassSnapshot snapshot, int level, PassTrack track) {
        if (snapshot == null || !snapshot.seasonActive() || snapshot.level() < level || claimed(snapshot, level, track)) {
            return false;
        }
        if (track == PassTrack.PREMIUM && !snapshot.premium()) {
            return false;
        }
        return tier(level, track).isPresent();
    }

    /** Number of tiers the player could claim right now. */
    public int claimableCount(PassSnapshot snapshot) {
        if (snapshot == null || !snapshot.seasonActive()) {
            return 0;
        }
        int count = 0;
        for (int level = 1; level <= snapshot.level(); level++) {
            for (PassTrack track : PassTrack.values()) {
                if (claimable(snapshot, level, track)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The next tier of a track the player has not reached yet (empty at max level). */
    public Optional<PassTierSnapshot> nextReward(PassSnapshot snapshot, PassTrack track) {
        if (snapshot == null || !snapshot.seasonActive()) {
            return Optional.empty();
        }
        PassSeasonSnapshot current = season().orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        return current.tiers().stream()
                .filter(tier -> tier.track() == track && tier.level() > snapshot.level())
                .min(Comparator.comparingInt(PassTierSnapshot::level));
    }

    /** Quests of a scope the player can see (premium-only quests are hidden without premium). */
    public List<PassQuestSnapshot> quests(PassSnapshot snapshot, de.tasticgames.pass.PassQuestScope scope) {
        if (snapshot == null) {
            return List.of();
        }
        List<PassQuestSnapshot> quests = new ArrayList<>();
        for (PassQuestSnapshot quest : snapshot.quests()) {
            if (quest.scope() == scope && (!quest.premiumOnly() || snapshot.premium())) {
                quests.add(quest);
            }
        }
        quests.sort(Comparator.comparingInt(PassQuestSnapshot::sortOrder).thenComparing(PassQuestSnapshot::questKey));
        return quests;
    }

    public static long questsCompleted(PassSnapshot snapshot) {
        return snapshot == null ? 0 : snapshot.quests().stream().filter(PassQuestSnapshot::completed).count();
    }
}
