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
    private volatile String credentialSource = "none";
    private volatile boolean credentialsRejected;
    private volatile boolean apiOutdated;
    private volatile long lastAuthErrorLogAt;

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
        credentialSource = api.credentialSource();
        credentialsRejected = false;
        apiOutdated = false;
        if (!api.credentialsConfigured()) {
            enabled = false;
            logger.severe("TasticGames API is NOT configured for TasticLobby: no API key found in the environment (TASTIC_API_KEY), "
                    + "config/api.yml or plugins/TasticCore/config/api.yml" + (api.baseUrl().isBlank() ? " and no base-url" : "")
                    + ". Cookie Clicker, cosmetics, social and gateway answer 'temporarily unavailable' until this is fixed.");
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
        logger.info("TasticGames API client initialized for " + base + " (service " + api.serviceName() + ", credentials from " + api.credentialSource() + ").");
        call("health", c -> c.health()).whenComplete((health, throwable) -> {
            if (throwable == null) {
                logger.info("API connection established: " + health.service() + " " + health.version() + " [" + health.status() + "]");
                verifyAuthenticatedEndpoint();
            } else {
                logger.warning("API health check failed: " + de.tasticgames.lobby.util.LobbyThrowables.rootMessage(throwable)
                        + " – calls are retried on demand.");
            }
        });
    }

    /**
     * /health is unauthenticated; the first authenticated 1.0 endpoint tells apart wrong credentials
     * (401/403) and an outdated API without the network/social/lobby endpoints (404).
     */
    private void verifyAuthenticatedEndpoint() {
        call("network.servers", c -> c.network().listServers()).whenComplete((servers, throwable) -> {
            if (throwable == null) {
                logger.info("API authentication verified (" + servers.size() + " network servers registered).");
                return;
            }
            Throwable cause = de.tasticgames.lobby.util.LobbyThrowables.unwrap(throwable);
            if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 404) {
                apiOutdated = true;
                logger.severe("The TasticGames API at " + configurationService.configuration().api().baseUrl()
                        + " does not know the 1.0 endpoints (GET /api/v1/network/servers -> 404). Deploy tasticgames-api 1.0 "
                        + "(Flyway V5-V15) – until then network, social, cosmetics and Cookie Clicker report 'temporarily unavailable'.");
            }
        });
    }

    public boolean credentialsRejected() {
        return credentialsRejected;
    }

    public boolean apiOutdated() {
        return apiOutdated;
    }

    public String credentialSource() {
        return credentialSource;
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
        return enabled() && consecutiveFailures.get() == 0 && !credentialsRejected && !apiOutdated;
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
                if (cause instanceof de.tasticgames.client.internal.HttpException http && (http.statusCode() == 401 || http.statusCode() == 403)) {
                    authFailure(operation, http);
                } else if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() >= 400 && http.statusCode() < 500) {
                    ok(); // the API answered: business error (404 not found, 409 conflict, 422 validation)
                    lastFailure = "HTTP " + http.statusCode() + " on " + operation;
                } else {
                    fail(operation, cause);
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            ok();
            return result;
        });
    }

    private void authFailure(String operation, de.tasticgames.client.internal.HttpException http) {
        credentialsRejected = true;
        lastFailure = "HTTP " + http.statusCode() + " (credentials rejected) on " + operation;
        consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastAuthErrorLogAt > 60_000) {
            lastAuthErrorLogAt = now;
            LobbyConfiguration.Api api = configurationService.configuration().api();
            logger.severe("The TasticGames API rejected the credentials of service '" + api.serviceName() + "' (HTTP " + http.statusCode()
                    + " on " + operation + "; key from " + credentialSource + "). Register the service in the API "
                    + "(tasticgames.security.service-auth.services." + api.serviceName() + ") or set TASTIC_API_KEY / config/api.yml.");
        }
    }

    private void ok() {
        if (credentialsRejected) {
            credentialsRejected = false;
            logger.info("TasticGames API accepted the credentials again.");
        }
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
