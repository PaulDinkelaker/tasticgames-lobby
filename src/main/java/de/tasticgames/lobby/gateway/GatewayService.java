package de.tasticgames.lobby.gateway;

import de.tasticgames.client.dto.network.MaintenanceStateResponse;
import de.tasticgames.client.dto.network.NetworkServerResponse;
import de.tasticgames.client.dto.network.ServerAdminStateResponse;
import de.tasticgames.client.dto.network.ServerHealthResponse;
import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.client.dto.network.TransferRequest;
import de.tasticgames.client.dto.network.TransferRequestResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gateway: cached network snapshot (server types, availability, players) and logical
 * transfer requests ("SURVIVAL") that TasticProxy routes. No backend names in the lobby.
 */
public final class GatewayService implements Service {

    public record ModeStatus(ServerTypeResponse type, boolean available, boolean maintenance, int players, int capacity, int servers) {
    }

    private final Plugin plugin;
    private final LobbyApiService api;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final Map<ServerTypeResponse, ModeStatus> statuses = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private volatile boolean maintenance;
    private volatile Instant snapshotAt;
    private BukkitTask task;

    public GatewayService(Plugin plugin, LobbyApiService api, LobbyTelemetryService telemetry, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.api = Objects.requireNonNull(api);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "gateway-service";
    }

    @Override
    public void start() {
        refresh();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 20L * 15, 20L * 15);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        statuses.clear();
    }

    public boolean available() {
        return api.enabled();
    }

    public Instant snapshotAt() {
        return snapshotAt;
    }

    public boolean networkMaintenance() {
        return maintenance;
    }

    public ModeStatus status(ServerTypeResponse type) {
        return statuses.getOrDefault(type, new ModeStatus(type, false, maintenance, 0, 0, 0));
    }

    public List<ModeStatus> modes() {
        return List.of(status(ServerTypeResponse.SURVIVAL), status(ServerTypeResponse.CREATIVE), status(ServerTypeResponse.DUELS));
    }

    public void refresh() {
        if (!api.enabled()) {
            return;
        }
        api.call("network.servers", c -> c.network().listServers()).whenComplete((servers, throwable) -> {
            if (throwable != null) {
                return;
            }
            Map<ServerTypeResponse, int[]> agg = new EnumMap<>(ServerTypeResponse.class);
            for (NetworkServerResponse server : servers) {
                int[] a = agg.computeIfAbsent(server.type(), t -> new int[4]);
                boolean ok = server.adminState() == ServerAdminStateResponse.ONLINE && server.health() != ServerHealthResponse.UNREACHABLE;
                if (ok) a[0] = 1;
                a[1] += server.playerCount();
                a[2] += server.capacity();
                a[3]++;
            }
            for (ServerTypeResponse type : ServerTypeResponse.values()) {
                int[] a = agg.getOrDefault(type, new int[4]);
                statuses.put(type, new ModeStatus(type, a[0] == 1, maintenance, a[1], a[2], a[3]));
            }
            snapshotAt = Instant.now();
        });
        api.call("network.maintenance", c -> c.network().getMaintenance()).whenComplete((state, throwable) -> {
            if (throwable == null) {
                maintenance = state.enabled();
            }
        });
    }

    /** Requests a routed transfer for the player (or the party when leader and partyTransfer). */
    public CompletableFuture<TransferRequestResponse> requestTransfer(Player player, ServerTypeResponse type, boolean partyTransfer) {
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(player.getUniqueId());
        if (last != null && now - last < 3000) {
            return CompletableFuture.completedFuture(new TransferRequestResponse(UUID.randomUUID(), false, null, "COOLDOWN"));
        }
        lastRequest.put(player.getUniqueId(), now);
        TransferRequest request = new TransferRequest(UUID.randomUUID(), player.getUniqueId(), type, null, partyTransfer,
                "gateway", api.serverId());
        telemetry.event("lobby.gateway.transfer_request", player.getUniqueId(), Map.of("target", type, "party", partyTransfer));
        return api.call("network.transfer", c -> c.network().requestTransfer(request));
    }

    public void forget(UUID player) {
        lastRequest.remove(player);
    }
}
