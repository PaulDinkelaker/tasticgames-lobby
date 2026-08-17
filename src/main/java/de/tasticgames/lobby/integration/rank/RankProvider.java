package de.tasticgames.lobby.integration.rank;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Rank / permission-node provider (LuckPerms preferred, Bukkit permission fallback).
 * All futures complete off the main thread unless noted; callers hop back with MainThread.
 */
public interface RankProvider extends Integration {

    /**
     * @param group       primary group id (lower case, e.g. {@code admin}); {@code default} when unknown
     * @param displayName human readable group name (LuckPerms display name or capitalised group id)
     * @param prefix      raw prefix (legacy/MiniMessage as configured in the permission plugin), may be empty
     * @param suffix      raw suffix, may be empty
     * @param weight      group weight (0 when unknown)
     */
    record RankInfo(String group, String displayName, String prefix, String suffix, int weight) {
        public static final RankInfo DEFAULT = new RankInfo("default", "Player", "", "", 0);
    }

    /** Never null; {@link RankInfo#DEFAULT} when nothing is known. Main-thread safe and cheap (cached data). */
    RankInfo rank(Player player);

    /** {@code true} when the (possibly offline) user has the permission node set (explicit node, not inheritance). */
    CompletableFuture<Boolean> hasPermissionNode(UUID uuid, String node);

    /**
     * Grants a permission node permanently and idempotently (offline users included).
     * Completes with {@code true} when the node is present afterwards.
     */
    CompletableFuture<Boolean> grantPermission(UUID uuid, String node);

    /** Removes a permission node; {@code true} when absent afterwards. */
    CompletableFuture<Boolean> revokePermission(UUID uuid, String node);

    /** Registers a listener invoked (any thread) when a player's rank data changed. */
    void onRankChanged(Consumer<UUID> listener);
}
