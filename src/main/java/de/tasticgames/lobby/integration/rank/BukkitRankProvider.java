package de.tasticgames.lobby.integration.rank;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Fallback without LuckPerms: the rank is derived from {@code group.<name>} permissions (the
 * convention most permission plugins expose); permission grants are not possible.
 */
public final class BukkitRankProvider implements RankProvider {

    private static final List<String> GROUPS = List.of("owner", "admin", "developer", "moderator", "builder", "helper", "team", "vip", "premium", "default");

    private final Logger logger;
    private volatile boolean grantWarned;

    public BukkitRankProvider(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String pluginName() {
        return "Bukkit";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String status() {
        return "permission fallback (group.<name>)";
    }

    @Override
    public RankInfo rank(Player player) {
        if (player == null) {
            return RankInfo.DEFAULT;
        }
        for (int i = 0; i < GROUPS.size(); i++) {
            String group = GROUPS.get(i);
            if (player.hasPermission("group." + group)) {
                return new RankInfo(group, RankNames.capitalise(group), "", "", GROUPS.size() - i);
            }
        }
        return RankInfo.DEFAULT;
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionNode(UUID uuid, String node) {
        Player player = Bukkit.getPlayer(uuid);
        return CompletableFuture.completedFuture(player != null && player.hasPermission(node));
    }

    @Override
    public CompletableFuture<Boolean> grantPermission(UUID uuid, String node) {
        warn();
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Boolean> revokePermission(UUID uuid, String node) {
        warn();
        return CompletableFuture.completedFuture(false);
    }

    private void warn() {
        if (!grantWarned) {
            grantWarned = true;
            logger.info("Permission node grants need LuckPerms – install it to sync cosmetic unlocks to permission based plugins.");
        }
    }

    @Override
    public void onRankChanged(Consumer<UUID> listener) {
        // no change events without a permission plugin
    }
}
