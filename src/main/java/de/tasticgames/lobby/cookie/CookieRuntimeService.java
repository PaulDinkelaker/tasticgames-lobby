package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieOfflineClaimRequest;
import de.tasticgames.client.dto.lobby.CookieOperationResponse;
import de.tasticgames.client.dto.lobby.CookiePrestigeRequest;
import de.tasticgames.client.dto.lobby.CookieProfileResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.OfflineResult;
import de.tasticgames.lobby.cookie.domain.model.PrestigePlan;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Cookie runtime: profile load/save (API source of truth, optimistic locking), 1s production tick,
 * dirty batching, crash safety (bounded dirty age), pause when the API stays unavailable,
 * transaction-safe prestige/offline claim through idempotent API operations.
 */
public final class CookieRuntimeService implements Service {

    private final Plugin plugin;
    private final LobbyApiService api;
    private final java.util.function.Supplier<CookieConfiguration> configurationSupplier;
    private final CookieEngine engine;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final Logger logger;
    private final Map<UUID, CookieSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<CookieSession>> loading = new ConcurrentHashMap<>();
    private final java.util.List<Consumer<CookieSession>> tickListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile java.util.function.BiConsumer<CookieSession, de.tasticgames.lobby.cookie.domain.model.CookieAmount> productionListener = (session, produced) -> {
    };
    private volatile java.util.function.Predicate<CookieSession> productionGate = session -> true;
    private volatile java.util.function.BiConsumer<CookieSession, Boolean> productionStateListener = (s, active) -> { };
    private volatile CookieProgressListener progress = CookieProgressListener.NONE;

    /** Consumer of prestige progress (season pass XP/metrics); {@link CookieProgressListener#NONE} by default. */
    public void setProgressListener(CookieProgressListener listener) {
        this.progress = Objects.requireNonNull(listener);
    }

    /** Generators/passive production only run while the gate allows it (inside the cookie zone / open world). */
    public void setProductionGate(java.util.function.Predicate<CookieSession> gate) {
        this.productionGate = Objects.requireNonNull(gate);
    }

    /** Notified (main thread) when a session's production switches between active and paused. */
    /** Receives every produced amount (idle production only, not clicks) – used by the shift orders. */
    public void setProductionListener(java.util.function.BiConsumer<CookieSession, de.tasticgames.lobby.cookie.domain.model.CookieAmount> listener) {
        this.productionListener = java.util.Objects.requireNonNull(listener, "listener");
    }

    public void setProductionStateListener(java.util.function.BiConsumer<CookieSession, Boolean> listener) {
        this.productionStateListener = Objects.requireNonNull(listener);
    }
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    public CookieRuntimeService(Plugin plugin, LobbyApiService api, java.util.function.Supplier<CookieConfiguration> configuration, CookieEngine engine,
                                LobbyTelemetryService telemetry, MainThread mainThread, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.api = Objects.requireNonNull(api);
        this.configurationSupplier = Objects.requireNonNull(configuration);
        this.engine = Objects.requireNonNull(engine);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cookie-runtime-service";
    }

    @Override
    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        long save = 20L * Math.max(3, configurationSupplier.get().runtime().saveIntervalSeconds());
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, save, save);
    }

    @Override
    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        // bounded flush of every dirty profile
        List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (CookieSession session : sessions.values()) {
            if (session.profile().isDirty()) {
                futures.add(save(session, true));
            }
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("Cookie flush on shutdown incomplete: " + LobbyThrowables.rootMessage(e));
        }
        sessions.clear();
        loading.clear();
    }

    public CookieEngine engine() {
        return engine;
    }

    public boolean available() {
        return api.enabled();
    }

    public void addTickListener(Consumer<CookieSession> listener) {
        tickListeners.add(listener);
    }

    public Optional<CookieSession> session(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    public Collection<CookieSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public long dirtyCount() {
        return sessions.values().stream().filter(s -> s.profile().isDirty()).count();
    }

    public long savingCount() {
        return sessions.values().stream().filter(s -> s.saving().get()).count();
    }

    /** Loads (or returns) the player's session; failures propagate (UI shows "unavailable"). */
    public CompletableFuture<CookieSession> load(UUID player) {
        CookieSession existing = sessions.get(player);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        if (!api.enabled()) {
            return CompletableFuture.failedFuture(new LobbyApiService.ApiUnavailableException());
        }
        return loading.computeIfAbsent(player, ignored -> api.call("cookie.load", c -> c.lobby().loadCookieProfile(player))
                .thenApply(response -> {
                    CookieProfile profile = CookieProfileMapper.fromResponse(response);
                    CookieSession session = new CookieSession(player, profile);
                    if (Bukkit.getPlayer(player) == null) {
                        // player left during the round trip: never keep a ghost session ticking/saving
                        throw new java.util.concurrent.CompletionException(new IllegalStateException("Player " + player + " is offline."));
                    }
                    CookieSession previous = sessions.putIfAbsent(player, session);
                    telemetry.event("cookie.session_started", player, Map.of("prestige", profile.prestigeLevel()));
                    return previous != null ? previous : session;
                })
                .whenComplete((s, t) -> loading.remove(player)));
    }

    /** Ends the session: final save (best effort) and removal. */
    public CompletableFuture<Void> unload(UUID player) {
        CookieSession session = sessions.remove(player);
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        session.profile().setLastActiveAt(Instant.now());
        session.profile().addPlaytimeSeconds(Duration.between(session.sessionStartedAt(), Instant.now()).toSeconds());
        session.profile().markDirty();
        telemetry.event("cookie.session_ended", player, Map.of("prestige", session.profile().prestigeLevel(),
                "cookies", session.profile().cookies().toPlainString()));
        return save(session, true).thenApply(r -> null);
    }

    /** Applies passive production, buff expiry, combo decay and achievements once per second (main thread). */
    private void tick() {
        Instant now = Instant.now();
        for (CookieSession session : sessions.values()) {
            if (session.paused()) {
                continue;
            }
            CookieProfile profile = session.profile();
            try {
                boolean active = productionGate.test(session);
                if (active != session.productionActive()) {
                    session.productionActive(active);
                    productionStateListener.accept(session, active);
                }
                if (active) {
                    productionListener.accept(session, engine.produce(profile, session.lastTickAt(), now));
                }
                engine.expireBuffs(profile, now);
                engine.decayCombo(profile, now);
                session.lastTickAt(now); // outside the zone the elapsed time is dropped, never back-filled
                if (profile.isDirty()) {
                    session.touchDirty();
                }
                for (Consumer<CookieSession> listener : tickListeners) {
                    listener.accept(session);
                }
            } catch (RuntimeException e) {
                logger.warning("Cookie tick failed for " + session.player() + ": " + LobbyThrowables.rootMessage(e));
            }
        }
    }

    private void saveDirty() {
        Instant now = Instant.now();
        int maxAge = configurationSupplier.get().runtime().maxDirtyAgeSeconds();
        for (CookieSession session : sessions.values()) {
            if (!session.profile().isDirty() || session.saving().get()) {
                continue;
            }
            Instant dirtySince = session.dirtySince();
            boolean due = dirtySince == null || Duration.between(dirtySince, now).getSeconds() >= 0;
            if (due) {
                save(session, false);
            }
            if (dirtySince != null && Duration.between(dirtySince, now).getSeconds() > maxAge * 4L
                    && session.saveFailures().get() >= configurationSupplier.get().runtime().maxSaveFailuresBeforePause() && !session.paused()) {
                session.paused(true);
                Bukkit.getPlayer(session.player());
                logger.warning("Cookie progress of " + session.player() + " paused: persistence unavailable for too long.");
            }
        }
    }

    /**
     * Persists the profile (async). The returned future completes <em>after</em> the new server version
     * was adopted on the main thread, so a follow-up operation (prestige, offline claim) that sends
     * {@code expectedVersion} never races the version bump. Version conflicts reload the server state.
     */
    public CompletableFuture<Boolean> save(CookieSession session, boolean force) {
        if (!api.enabled()) {
            session.saveFailures().incrementAndGet();
            return CompletableFuture.completedFuture(false);
        }
        if (!session.saving().compareAndSet(false, true)) {
            CompletableFuture<Boolean> inFlight = session.inFlightSave();
            if (!force || inFlight == null) {
                return CompletableFuture.completedFuture(false);
            }
            // forced saves (prestige, offline claim, quit) wait for the running save and re-check
            return inFlight.handle((r, t) -> null).thenCompose(ignored -> session.profile().isDirty() ? save(session, true) : CompletableFuture.completedFuture(true));
        }
        CookieProfile profile = session.profile();
        profile.setLastActiveAt(Instant.now());
        var request = CookieProfileMapper.toSaveRequest(profile);
        long expected = profile.version();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        session.inFlightSave(result);
        api.call("cookie.save", c -> c.lobby().saveCookieProfile(session.player(), request)).whenComplete((response, throwable) -> {
            if (throwable != null) {
                Throwable cause = LobbyThrowables.unwrap(throwable);
                if (isVersionConflict(cause)) {
                    logger.warning("Cookie version conflict for " + session.player() + " – reloading server state.");
                    reload(session).whenComplete((v, t) -> finishSave(session, result, false));
                    return;
                }
                int failures = session.saveFailures().incrementAndGet();
                if (failures == 1 || failures % 10 == 0) {
                    logger.warning("Cookie save failed for " + session.player() + " (" + failures + "x): " + LobbyThrowables.rootMessage(cause));
                }
                finishSave(session, result, false);
                return;
            }
            session.saveFailures().set(0);
            session.paused(false);
            onMainThread(() -> {
                if (profile.version() == expected) {
                    profile.setVersion(response.version());
                    profile.markClean();
                    session.lastSavedAt(Instant.now());
                } else {
                    // mutated during the save: keep dirty, but adopt the new version
                    profile.setVersion(response.version());
                }
                finishSave(session, result, true);
            });
        });
        return result;
    }

    private static void finishSave(CookieSession session, CompletableFuture<Boolean> result, boolean ok) {
        session.inFlightSave(null);
        session.saving().set(false);
        result.complete(ok);
    }

    /**
     * Main-thread hop that still runs during shutdown (the scheduler refuses new tasks once the plugin
     * is disabled; the main thread is then blocked in {@link #stop()} waiting for exactly these futures).
     */
    private void onMainThread(Runnable runnable) {
        if (Bukkit.isPrimaryThread() || !plugin.isEnabled()) {
            runnable.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    static boolean isVersionConflict(Throwable cause) {
        if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 409) {
            return true;
        }
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.contains("VERSION_CONFLICT");
    }

    /** Fetches the server state and applies it on the main thread; the future completes afterwards. */
    private CompletableFuture<Void> reload(CookieSession session) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        api.call("cookie.reload", c -> c.lobby().loadCookieProfile(session.player())).whenComplete((response, throwable) -> {
            if (throwable != null) {
                done.completeExceptionally(throwable);
                return;
            }
            onMainThread(() -> {
                try {
                    CookieProfileMapper.applyServerState(session.profile(), response);
                    session.lastSavedAt(Instant.now());
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        });
        return done;
    }

    /**
     * Transaction-safe prestige: engine plan → API (idempotent) → apply the server state locally. The
     * request is built on the main thread after the pre-flush completed (fresh {@code expectedVersion});
     * a version conflict (autosave/other server bumped the version meanwhile) syncs the server state and
     * retries with the same operation id (up to {@link #PRESTIGE_ATTEMPTS} attempts).
     */
    public CompletableFuture<PrestigeResult> prestige(CookieSession session) {
        CookieProfile profile = session.profile();
        PrestigePlan plan = engine.planPrestige(profile);
        if (!plan.eligible()) {
            return CompletableFuture.completedFuture(new PrestigeResult(false, "NOT_ELIGIBLE", plan));
        }
        UUID operationId = UUID.randomUUID();
        return flushBeforeOperation(session).thenCompose(ok -> prestigeAttempt(session, plan, operationId, 1));
    }

    static final int PRESTIGE_ATTEMPTS = 3;

    private CompletableFuture<PrestigeResult> prestigeAttempt(CookieSession session, PrestigePlan plan, UUID operationId, int attempt) {
        CookieProfile profile = session.profile();
        return mainThread.supply(() -> new CookiePrestigeRequest(operationId, profile.version(), plan.fromLevel(), plan.toLevel(),
                        profile.lifetimeCookies().toPlainString(), Long.toString(plan.crumbsGained()), List.copyOf(profile.achievements()),
                        List.copyOf(profile.discoveredZones()), plan.rewardCosmeticIds()))
                .thenCompose(request -> api.call("cookie.prestige", c -> c.lobby().prestige(session.player(), request)))
                .thenCompose(response -> mainThread.supply(() -> applyPrestigeResponse(session, plan, response)))
                .exceptionallyCompose(throwable -> {
                    Throwable cause = LobbyThrowables.unwrap(throwable);
                    if (attempt < PRESTIGE_ATTEMPTS && isVersionConflict(cause)) {
                        logger.info("Cookie prestige for " + session.player() + ": version conflict on attempt " + attempt + " – syncing server state and retrying.");
                        return reload(session).thenCompose(v -> prestigeAttempt(session, plan, operationId, attempt + 1));
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    private PrestigeResult applyPrestigeResponse(CookieSession session, PrestigePlan plan, de.tasticgames.client.dto.lobby.CookieOperationResponse response) {
        CookieProfile profile = session.profile();
        {
            if (response.applied()) {
                engine.applyPrestige(profile, plan);
                CookieProfileMapper.applyServerState(profile, response.profile());
                // starting cookies/generators from the tree are part of the local reset; persist them right away
                profile.markDirty();
                save(session, true);
                telemetry.event("cookie.prestige_completed", session.player(), Map.of("to", plan.toLevel(), "crumbs", plan.crumbsGained()));
                progress.onPrestige(session.player(), plan.toLevel());
                return new PrestigeResult(true, response.outcome(), plan);
            }
            if (response.duplicate()) {
                CookieProfileMapper.applyServerState(profile, response.profile());
                return new PrestigeResult(true, "DUPLICATE", plan);
            }
            CookieProfileMapper.applyServerState(profile, response.profile());
            return new PrestigeResult(false, response.outcome(), plan);
        }
    }

    public record PrestigeResult(boolean applied, String outcome, PrestigePlan plan) {
    }

    /** Offline production preview + idempotent claim. */
    public Optional<OfflineResult> previewOffline(CookieSession session) {
        CookieProfile profile = session.profile();
        OfflineResult result = engine.previewOffline(profile, profile.lastActiveAt(), Instant.now());
        return result.cookies().isZero() ? Optional.empty() : Optional.of(result);
    }

    public CompletableFuture<CookieOperationResponse> claimOffline(CookieSession session, OfflineResult result) {
        Instant now = Instant.now();
        CookieOfflineClaimRequest request = new CookieOfflineClaimRequest(UUID.randomUUID(), session.profile().version(),
                result.cookies().toPlainString(), result.seconds(), now);
        return flushBeforeOperation(session).thenCompose(ok -> api.call("cookie.offline", c -> c.lobby().claimOffline(session.player(), request)))
                .thenCompose(response -> mainThread.supply(() -> {
                    CookieProfileMapper.applyServerState(session.profile(), response.profile());
                    session.profile().setLastActiveAt(now);
                    if (response.applied()) {
                        telemetry.event("cookie.offline_reward_claimed", session.player(), Map.of("cookies", result.cookies().toPlainString(), "seconds", result.seconds()));
                    }
                    return response;
                }));
    }

    /** Ensures the current local state is persisted before a server-side operation uses expectedVersion. */
    private CompletableFuture<Boolean> flushBeforeOperation(CookieSession session) {
        if (!session.profile().isDirty()) {
            return CompletableFuture.completedFuture(true);
        }
        return save(session, true).thenApply(saved -> {
            if (!saved) {
                throw new IllegalStateException("Cookie state could not be persisted.");
            }
            return true;
        });
    }

    /** Re-applies server state after an admin mutation. */
    public void applyServerState(UUID player, CookieProfileResponse response) {
        CookieSession session = sessions.get(player);
        if (session != null) {
            mainThread.run(() -> CookieProfileMapper.applyServerState(session.profile(), response));
        }
    }
}
