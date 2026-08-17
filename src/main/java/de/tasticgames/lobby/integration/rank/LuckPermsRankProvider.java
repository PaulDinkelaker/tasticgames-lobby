package de.tasticgames.lobby.integration.rank;

import de.tasticgames.lobby.integration.Integration;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeEqualityPredicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * LuckPerms backed ranks: primary group, display name, prefix/suffix (cached meta), weight, and
 * permanent permission-node grants (used to hand HMCCosmetics/other permission based unlocks to
 * players when TasticGames unlocks a cosmetic).
 */
public final class LuckPermsRankProvider implements RankProvider {

    private final Plugin plugin;
    private final Logger logger;
    private final List<Consumer<UUID>> listeners = new CopyOnWriteArrayList<>();
    private volatile LuckPerms luckPerms;
    private EventSubscription<UserDataRecalculateEvent> subscription;
    private volatile boolean warned;

    public LuckPermsRankProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            luckPerms = LuckPermsProvider.get();
            subscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
                UUID uuid = event.getUser().getUniqueId();
                for (Consumer<UUID> listener : listeners) {
                    try {
                        listener.accept(uuid);
                    } catch (RuntimeException e) {
                        logger.warning("Rank change listener failed: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            luckPerms = null;
            logger.warning("LuckPerms hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – using Bukkit permission fallback.");
        }
    }

    public void unhook() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Throwable ignored) {
                // LuckPerms may already be disabled
            }
            subscription = null;
        }
        luckPerms = null;
        listeners.clear();
    }

    @Override
    public String pluginName() {
        return "LuckPerms";
    }

    @Override
    public boolean available() {
        return luckPerms != null;
    }

    @Override
    public RankInfo rank(Player player) {
        LuckPerms api = luckPerms;
        if (api == null || player == null) {
            return RankInfo.DEFAULT;
        }
        try {
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                return RankInfo.DEFAULT;
            }
            String primary = user.getPrimaryGroup() == null ? "default" : user.getPrimaryGroup().toLowerCase(Locale.ROOT);
            CachedMetaData meta = user.getCachedData().getMetaData();
            String prefix = meta.getPrefix() == null ? "" : meta.getPrefix();
            String suffix = meta.getSuffix() == null ? "" : meta.getSuffix();
            Group group = api.getGroupManager().getGroup(primary);
            String display = group != null && group.getDisplayName() != null ? group.getDisplayName() : RankNames.capitalise(primary);
            int weight = group == null ? 0 : weightOf(group);
            return new RankInfo(primary, display, prefix, suffix, weight);
        } catch (Throwable t) {
            warnOnce("rank lookup", t);
            return RankInfo.DEFAULT;
        }
    }

    private static int weightOf(Group group) {
        OptionalInt weight = group.getWeight();
        return weight.isPresent() ? weight.getAsInt() : 0;
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionNode(UUID uuid, String node) {
        LuckPerms api = luckPerms;
        if (api == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            return api.getUserManager().loadUser(uuid).thenApply(user -> user != null && contains(user, node)).exceptionally(t -> {
                warnOnce("hasPermissionNode", t);
                return false;
            });
        } catch (Throwable t) {
            warnOnce("hasPermissionNode", t);
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public CompletableFuture<Boolean> grantPermission(UUID uuid, String node) {
        return mutate(uuid, node, true);
    }

    @Override
    public CompletableFuture<Boolean> revokePermission(UUID uuid, String node) {
        return mutate(uuid, node, false);
    }

    private CompletableFuture<Boolean> mutate(UUID uuid, String node, boolean add) {
        LuckPerms api = luckPerms;
        if (api == null || node == null || node.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            return api.getUserManager().loadUser(uuid).thenCompose(user -> {
                if (user == null) {
                    return CompletableFuture.completedFuture(false);
                }
                Node permission = Node.builder(node).build();
                boolean present = contains(user, node);
                if (add == present) {
                    return CompletableFuture.completedFuture(true);
                }
                if (add) {
                    user.data().add(permission);
                } else {
                    user.data().remove(permission);
                }
                return api.getUserManager().saveUser(user).thenApply(v -> contains(user, node) == add);
            }).exceptionally(t -> {
                warnOnce(add ? "grantPermission" : "revokePermission", t);
                return false;
            });
        } catch (Throwable t) {
            warnOnce(add ? "grantPermission" : "revokePermission", t);
            return CompletableFuture.completedFuture(false);
        }
    }

    private static boolean contains(User user, String node) {
        Node permission = Node.builder(node).build();
        return user.data().contains(permission, NodeEqualityPredicate.IGNORE_EXPIRY_TIME).asBoolean();
    }

    @Override
    public void onRankChanged(Consumer<UUID> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("LuckPerms " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
