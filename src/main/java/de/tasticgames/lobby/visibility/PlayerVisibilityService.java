package de.tasticgames.lobby.visibility;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player visibility (ALL / FRIENDS / PARTY / FRIENDS_AND_PARTY / NONE) via Paper show/hide APIs.
 * Staff with {@code tasticlobby.visibility.always} stay visible.
 */
public final class PlayerVisibilityService implements Service {

    public static final String ALWAYS_VISIBLE = "tasticlobby.visibility.always";

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final SocialSnapshotService social;

    public PlayerVisibilityService(Plugin plugin, TasticCoreApi coreApi, SocialSnapshotService social) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.social = Objects.requireNonNull(social);
    }

    @Override
    public String id() {
        return "player-visibility-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(other) && !viewer.canSee(other)) {
                    viewer.showPlayer(plugin, other);
                }
            }
        }
    }

    public VisibilityMode modeOf(Player player) {
        return coreApi.playerManager().find(player.getUniqueId())
                .map(p -> VisibilityMode.find(p.settings().get(LobbySettings.PLAYER_VISIBILITY)).orElse(VisibilityMode.ALL))
                .orElse(VisibilityMode.ALL);
    }

    public CompletableFuture<VisibilityMode> cycle(Player player) {
        VisibilityMode next = modeOf(player).next();
        return set(player, next);
    }

    public CompletableFuture<VisibilityMode> set(Player player, VisibilityMode mode) {
        TasticPlayer tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic == null) {
            return CompletableFuture.completedFuture(modeOf(player));
        }
        return coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.PLAYER_VISIBILITY, mode.name())
                .thenApply(change -> mode);
    }

    /** Recomputes what {@code viewer} sees. Runs on the main thread. */
    public void apply(Player viewer) {
        VisibilityMode mode = modeOf(viewer);
        Set<UUID> friends = social.friendsOf(viewer.getUniqueId());
        Set<UUID> party = social.partyMembersOf(viewer.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) {
                continue;
            }
            boolean visible = switch (mode) {
                case ALL -> true;
                case FRIENDS -> friends.contains(other.getUniqueId());
                case PARTY -> party.contains(other.getUniqueId());
                case FRIENDS_AND_PARTY -> friends.contains(other.getUniqueId()) || party.contains(other.getUniqueId());
                case NONE -> false;
            } || other.hasPermission(ALWAYS_VISIBLE);
            if (visible && !viewer.canSee(other)) {
                viewer.showPlayer(plugin, other);
            } else if (!visible && viewer.canSee(other)) {
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    /** Applies every viewer's mode towards a newly joined player and the joiner's own mode. */
    public void applyAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            apply(viewer);
        }
    }
}
