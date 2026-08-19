package de.tasticgames.lobby.network;

import de.tasticgames.client.dto.network.ProxyInstanceResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * How many players are online on the whole network, not just on this server.
 * <p>
 * Every proxy reports its player count to the API with its heartbeat, so the sum over the proxies that are not
 * stale is the network figure – no extra endpoint and no cross-server chatter needed. The value is polled in
 * the background and cached; while the API is unreachable the HUD falls back to the players on this server,
 * which is never larger than the truth.
 */
public final class NetworkOnlineService implements Service {

    private static final long REFRESH_TICKS = 20L * 20;
    /** After this long without a successful refresh the cached number is not shown any more. */
    private static final long STALE_MILLIS = 120_000L;

    private final Plugin plugin;
    private final LobbyApiService api;
    private final Logger logger;
    private final AtomicInteger networkPlayers = new AtomicInteger(-1);
    private final AtomicLong lastRefreshAt = new AtomicLong();
    private final AtomicInteger proxies = new AtomicInteger();
    private BukkitTask task;
    private volatile boolean warned;

    public NetworkOnlineService(Plugin plugin, LobbyApiService api, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.api = Objects.requireNonNull(api, "api");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "network-online";
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 20L, REFRESH_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        networkPlayers.set(-1);
    }

    /**
     * Players online across every proxy, or the players on this server while the network figure is unknown.
     * Never smaller than the local count – this server's players are part of the network too.
     */
    public int onlinePlayers() {
        int local = Bukkit.getOnlinePlayers().size();
        int network = networkPlayers.get();
        if (network < 0 || System.currentTimeMillis() - lastRefreshAt.get() > STALE_MILLIS) {
            return local;
        }
        return Math.max(network, local);
    }

    /** Whether the number really is the network figure (false = local fallback). */
    public boolean networkWide() {
        return networkPlayers.get() >= 0 && System.currentTimeMillis() - lastRefreshAt.get() <= STALE_MILLIS;
    }

    /** Proxies that reported the number, for {@code /tasticlobby status}. */
    public int proxyCount() {
        return proxies.get();
    }

    private void refresh() {
        api.call("network.proxies", client -> client.network().listProxies())
                .whenComplete((list, throwable) -> {
                    if (throwable != null) {
                        if (!warned) {
                            warned = true;
                            logger.info("Network player count unavailable (" + LobbyThrowables.rootMessage(throwable)
                                    + ") – the HUD shows this server until the API answers again.");
                        }
                        return;
                    }
                    warned = false;
                    apply(list);
                });
    }

    private void apply(List<ProxyInstanceResponse> list) {
        if (list == null) {
            return;
        }
        int sum = 0;
        int counted = 0;
        for (ProxyInstanceResponse proxy : list) {
            if (proxy == null || proxy.stale()) {
                continue; // a proxy that stopped reporting would keep its last count forever
            }
            sum += Math.max(0, proxy.playerCount());
            counted++;
        }
        proxies.set(counted);
        networkPlayers.set(counted == 0 ? -1 : sum);
        lastRefreshAt.set(System.currentTimeMillis());
    }
}
