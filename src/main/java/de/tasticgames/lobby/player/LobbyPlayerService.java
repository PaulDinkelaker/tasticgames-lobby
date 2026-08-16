package de.tasticgames.lobby.player;

import de.tasticgames.service.Service;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of lobby runtime players.
 */
public final class LobbyPlayerService implements Service {

    private final ConcurrentMap<UUID, LobbyPlayer> players = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "lobby-player-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        players.clear();
    }

    public LobbyPlayer getOrCreate(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), uuid -> new LobbyPlayer(uuid, player.getName()));
    }

    public Optional<LobbyPlayer> find(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public LobbyPlayer require(UUID uuid) {
        return find(uuid).orElseThrow(() -> new IllegalStateException("Lobby player not loaded: " + uuid));
    }

    public Optional<LobbyPlayer> remove(UUID uuid) {
        return Optional.ofNullable(players.remove(uuid));
    }

    public Collection<LobbyPlayer> all() {
        return List.copyOf(players.values());
    }

    public long initializedCount() {
        return players.values().stream().filter(LobbyPlayer::initialized).count();
    }

    public int size() {
        return players.size();
    }
}
