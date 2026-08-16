package de.tasticgames.lobby.api;

import de.tasticgames.client.LobbyApi;
import de.tasticgames.client.NetworkApi;
import de.tasticgames.client.SocialApi;
import de.tasticgames.client.TasticApiClient;
import de.tasticgames.client.config.ApiClientConfiguration;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.service.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Lobby side wrapper of the shared TasticGames API client (cookie, cosmetics, preferences,
 * social reads, network snapshot, transfer requests, telemetry). Tracks availability so
 * every UI can show "temporarily unavailable" instead of fake data.
 */
public final class LobbyApiService implements Service {

    private final LobbyConfigurationService configurationService;
    private final Logger logger;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile TasticApiClient client;
    private volatile boolean enabled;
    private volatile Instant lastSuccessAt;
    private volatile String lastFailure = "";
    private volatile String serverId = "lobby";

    public LobbyApiService(LobbyConfigurationService configurationService, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-api-service";
    }

    @Override
    public void start() {
        LobbyConfiguration.Api api = configurationService.configuration().api();
        serverId = api.backendServerId();
        if (!api.credentialsConfigured()) {
            enabled = false;
            logger.warning("TasticGames API is not configured for TasticLobby (config/api.yml or TASTIC_API_KEY). "
                    + "Cookie clicker, cosmetics, social and gateway run in degraded mode.");
            return;
        }
        String base = api.baseUrl().endsWith("/") ? api.baseUrl() : api.baseUrl() + "/";
        client = new TasticApiClient(ApiClientConfiguration.builder()
                .baseUri(URI.create(base))
                .connectTimeout(Duration.ofSeconds(api.connectTimeoutSeconds()))
                .requestTimeout(Duration.ofSeconds(api.requestTimeoutSeconds()))
                .serviceName(api.serviceName())
                .apiKey(api.apiKey())
                .build());
        enabled = true;
        logger.info("TasticGames API client initialized for " + base + " (service " + api.serviceName() + ").");
        call("health", c -> c.health()).whenComplete((health, throwable) -> {
            if (throwable == null) {
                logger.info("API connection established: " + health.service() + " " + health.version() + " [" + health.status() + "]");
            }
        });
    }

    @Override
    public void stop() {
        enabled = false;
        TasticApiClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    public boolean enabled() {
        return enabled && client != null;
    }

    public boolean healthy() {
        return enabled() && consecutiveFailures.get() == 0;
    }

    public String serverId() {
        return serverId;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public LobbyApi lobby() {
        return require().lobby();
    }

    public SocialApi social() {
        return require().social();
    }

    public NetworkApi network() {
        return require().network();
    }

    public <T> CompletableFuture<T> call(String operation, Function<TasticApiClient, CompletableFuture<T>> invocation) {
        TasticApiClient current = client;
        if (!enabled || current == null) {
            return CompletableFuture.failedFuture(new ApiUnavailableException());
        }
        CompletableFuture<T> future;
        try {
            future = invocation.apply(current);
        } catch (RuntimeException e) {
            fail(operation, e);
            return CompletableFuture.failedFuture(e);
        }
        return future.handle((result, throwable) -> {
            if (throwable != null) {
                Throwable cause = de.tasticgames.lobby.util.LobbyThrowables.unwrap(throwable);
                if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() >= 400 && http.statusCode() < 500) {
                    ok();
                } else {
                    fail(operation, cause);
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            ok();
            return result;
        });
    }

    private void ok() {
        lastSuccessAt = Instant.now();
        int failures = consecutiveFailures.getAndSet(0);
        if (failures > 0) {
            logger.info("TasticGames API recovered after " + failures + " failure(s).");
        }
    }

    private void fail(String operation, Throwable cause) {
        lastFailure = de.tasticgames.lobby.util.LobbyThrowables.rootMessage(cause);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures == 1 || failures % 20 == 0) {
            logger.warning("TasticGames API call '" + operation + "' failed (" + failures + "x): " + lastFailure);
        }
    }

    private TasticApiClient require() {
        TasticApiClient current = client;
        if (!enabled || current == null) {
            throw new ApiUnavailableException();
        }
        return current;
    }

    public static final class ApiUnavailableException extends RuntimeException {
        public ApiUnavailableException() {
            super("TasticGames API is not available.");
        }
    }
}
