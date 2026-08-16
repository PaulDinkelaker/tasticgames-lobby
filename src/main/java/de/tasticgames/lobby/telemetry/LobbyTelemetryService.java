package de.tasticgames.lobby.telemetry;

import de.tasticgames.client.dto.network.TelemetryBatchRequest;
import de.tasticgames.client.dto.network.TelemetryEventRequest;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Lobby telemetry: bounded queue, async batches to the API, never blocks gameplay.
 */
public final class LobbyTelemetryService implements Service {

    private final Plugin plugin;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final Logger logger;
    private volatile BlockingQueue<TelemetryEventRequest> queue;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private volatile int backoffCycles;
    private BukkitTask task;

    public LobbyTelemetryService(Plugin plugin, LobbyConfigurationService configurationService, LobbyApiService api, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-telemetry-service";
    }

    @Override
    public void start() {
        LobbyConfiguration.Telemetry config = configurationService.configuration().telemetry();
        queue = new ArrayBlockingQueue<>(Math.max(100, config.queueCapacity()));
        long period = 20L * Math.max(1, config.flushIntervalSeconds());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, period, period);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // bounded final flush
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current != null && !current.isEmpty() && api.enabled()) {
            List<TelemetryEventRequest> batch = new ArrayList<>();
            current.drainTo(batch, 200);
            try {
                api.call("telemetry", c -> c.network().publishTelemetry(new TelemetryBatchRequest(batch))).get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    public boolean event(String type, UUID player, Map<String, ?> attributes) {
        return event(type, player, null, attributes);
    }

    public boolean event(String type, UUID player, String outcome, Map<String, ?> attributes) {
        if (!configurationService.configuration().telemetry().enabled() || !api.enabled()) {
            return false;
        }
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current == null) {
            return false;
        }
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attributes.forEach((k, v) -> {
            if (k != null && v != null) attrs.put(k, String.valueOf(v));
        });
        TelemetryEventRequest request = new TelemetryEventRequest(UUID.randomUUID(), type, Instant.now(), api.serverId(),
                null, null, player, null, api.serverId(), null, outcome, null, attrs);
        if (!current.offer(request)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                logger.warning("Lobby telemetry queue full – dropped " + count + " event(s) so far.");
            }
            return false;
        }
        return true;
    }

    public int queueSize() {
        BlockingQueue<TelemetryEventRequest> current = queue;
        return current == null ? 0 : current.size();
    }

    public long dropped() {
        return dropped.get();
    }

    public long published() {
        return published.get();
    }

    void flush() {
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current == null || current.isEmpty() || !api.enabled()) {
            return;
        }
        if (backoffCycles > 0) {
            backoffCycles--;
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        List<TelemetryEventRequest> batch = new ArrayList<>();
        current.drainTo(batch, configurationService.configuration().telemetry().batchSize());
        api.call("telemetry", c -> c.network().publishTelemetry(new TelemetryBatchRequest(batch))).whenComplete((r, t) -> {
            try {
                if (t != null) {
                    backoffCycles = Math.min(12, Math.max(1, backoffCycles * 2));
                    for (TelemetryEventRequest e : batch) {
                        if (!current.offer(e)) dropped.incrementAndGet();
                    }
                } else {
                    backoffCycles = 0;
                    published.addAndGet(batch.size());
                }
            } finally {
                flushing.set(false);
            }
        });
    }
}
