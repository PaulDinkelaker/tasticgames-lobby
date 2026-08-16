package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieLeaderboardResponse;
import de.tasticgames.client.dto.lobby.CookieLeaderboardTypeResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.service.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Cached leaderboard reads (never queried per tick).
 */
public final class CookieLeaderboardService implements Service {

    private record Cached(CookieLeaderboardResponse response, Instant loadedAt) {
    }

    private final LobbyApiService api;
    private final Duration ttl;
    private final Map<CookieLeaderboardTypeResponse, Cached> cache = new EnumMap<>(CookieLeaderboardTypeResponse.class);
    private final Map<CookieLeaderboardTypeResponse, CompletableFuture<CookieLeaderboardResponse>> loading = new EnumMap<>(CookieLeaderboardTypeResponse.class);

    public CookieLeaderboardService(LobbyApiService api, Duration ttl) {
        this.api = Objects.requireNonNull(api);
        this.ttl = Objects.requireNonNull(ttl);
    }

    @Override
    public String id() {
        return "cookie-leaderboard-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        synchronized (cache) {
            cache.clear();
            loading.clear();
        }
    }

    public synchronized CompletableFuture<CookieLeaderboardResponse> get(CookieLeaderboardTypeResponse type, int limit) {
        Cached cached = cache.get(type);
        if (cached != null && cached.loadedAt().plus(ttl).isAfter(Instant.now())) {
            return CompletableFuture.completedFuture(cached.response());
        }
        CompletableFuture<CookieLeaderboardResponse> pending = loading.get(type);
        if (pending != null) {
            return pending;
        }
        CompletableFuture<CookieLeaderboardResponse> future = api.call("cookie.leaderboard", c -> c.lobby().cookieLeaderboard(type, limit))
                .whenComplete((response, throwable) -> {
                    synchronized (this) {
                        loading.remove(type);
                        if (throwable == null) {
                            cache.put(type, new Cached(response, Instant.now()));
                        }
                    }
                });
        loading.put(type, future);
        return future;
    }
}
