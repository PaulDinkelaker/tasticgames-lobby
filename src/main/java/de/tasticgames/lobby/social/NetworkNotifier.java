package de.tasticgames.lobby.social;

import de.tasticgames.client.dto.network.NetworkCommandRequest;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.util.FlatJson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sends localized network notifications to any player on the network through the TasticProxy
 * command bus ({@code player.message} commands, rendered by the proxy with its own message keys).
 */
public final class NetworkNotifier {

    private final LobbyApiService api;

    public NetworkNotifier(LobbyApiService api) {
        this.api = Objects.requireNonNull(api);
    }

    public CompletableFuture<Void> notify(UUID target, String proxyMessageKey, Map<String, ?> placeholders) {
        if (!api.enabled() || target == null) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("key", proxyMessageKey);
        placeholders.forEach((k, v) -> payload.put("p." + k, String.valueOf(v)));
        NetworkCommandRequest request = new NetworkCommandRequest(UUID.randomUUID(), "player.message", api.serverId(), null, target,
                FlatJson.encode(payload), Instant.now().plusSeconds(60));
        return api.call("notify", c -> c.network().submitCommand(request)).thenApply(r -> null);
    }
}
