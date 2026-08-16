package de.tasticgames.lobby.social;

import de.tasticgames.client.dto.network.NetworkPresenceResponse;
import de.tasticgames.client.dto.social.ClanResponse;
import de.tasticgames.client.dto.social.FriendListResponse;
import de.tasticgames.client.dto.social.FriendResponse;
import de.tasticgames.client.dto.social.PartyMemberResponse;
import de.tasticgames.client.dto.social.PartyResponse;
import de.tasticgames.client.dto.network.BulkPresenceRequest;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.service.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-side social snapshots (friends, party, clan, presence) with a short TTL cache.
 * TasticProxy/API remain the source of truth; the lobby only renders.
 */
public final class SocialSnapshotService implements Service {

    public record Snapshot(FriendListResponse friends, PartyResponse party, ClanResponse clan,
                           Map<UUID, NetworkPresenceResponse> presence, Instant loadedAt) {
        public Set<UUID> friendUuids() {
            return friends == null ? Set.of() : friends.friends().stream().map(FriendResponse::minecraftUuid).collect(java.util.stream.Collectors.toSet());
        }

        public Set<UUID> partyUuids() {
            return party == null ? Set.of() : party.members().stream().map(PartyMemberResponse::minecraftUuid).collect(java.util.stream.Collectors.toSet());
        }

        public boolean online(UUID uuid) {
            NetworkPresenceResponse p = presence.get(uuid);
            return p != null && p.online();
        }

        public String serverTypeOf(UUID uuid) {
            NetworkPresenceResponse p = presence.get(uuid);
            return p == null || !p.online() ? null : p.currentServerType() == null ? "online" : p.currentServerType().name();
        }
    }

    private static final Duration TTL = Duration.ofSeconds(15);

    private final LobbyApiService api;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Snapshot>> loading = new ConcurrentHashMap<>();

    public SocialSnapshotService(LobbyApiService api) {
        this.api = Objects.requireNonNull(api);
    }

    @Override
    public String id() {
        return "social-snapshot-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        cache.clear();
        loading.clear();
    }

    public boolean available() {
        return api.enabled();
    }

    public Optional<Snapshot> cached(UUID player) {
        Snapshot snapshot = cache.get(player);
        return snapshot == null ? Optional.empty() : Optional.of(snapshot);
    }

    public Set<UUID> friendsOf(UUID player) {
        return cached(player).map(Snapshot::friendUuids).orElse(Set.of());
    }

    public Set<UUID> partyMembersOf(UUID player) {
        return cached(player).map(Snapshot::partyUuids).orElse(Set.of());
    }

    public void invalidate(UUID player) {
        cache.remove(player);
    }

    /** Returns a fresh-enough snapshot (cache within TTL) or loads it. */
    public CompletableFuture<Snapshot> load(UUID player, boolean force) {
        Snapshot cachedSnapshot = cache.get(player);
        if (!force && cachedSnapshot != null && cachedSnapshot.loadedAt().plus(TTL).isAfter(Instant.now())) {
            return CompletableFuture.completedFuture(cachedSnapshot);
        }
        return loading.computeIfAbsent(player, ignored -> fetch(player).whenComplete((s, t) -> loading.remove(player)));
    }

    private CompletableFuture<Snapshot> fetch(UUID player) {
        CompletableFuture<FriendListResponse> friends = api.call("social.friends", c -> c.social().friends(player));
        CompletableFuture<Optional<PartyResponse>> party = api.call("social.party", c -> c.social().partyOf(player));
        CompletableFuture<Optional<ClanResponse>> clan = api.call("social.clan", c -> c.social().clanOf(player));
        return CompletableFuture.allOf(friends, party, clan).thenCompose(ignored -> {
            FriendListResponse f = friends.join();
            PartyResponse p = party.join().orElse(null);
            ClanResponse cl = clan.join().orElse(null);
            Set<UUID> uuids = new HashSet<>();
            f.friends().forEach(x -> uuids.add(x.minecraftUuid()));
            if (p != null) p.members().forEach(m -> uuids.add(m.minecraftUuid()));
            if (cl != null) cl.members().forEach(m -> uuids.add(m.minecraftUuid()));
            CompletableFuture<List<NetworkPresenceResponse>> presence = uuids.isEmpty()
                    ? CompletableFuture.completedFuture(List.of())
                    : api.call("social.presence", c -> c.network().bulkPresence(new BulkPresenceRequest(List.copyOf(uuids))));
            return presence.exceptionally(t -> List.of()).thenApply(list -> {
                Map<UUID, NetworkPresenceResponse> byUuid = new HashMap<>();
                list.forEach(x -> byUuid.put(x.minecraftUuid(), x));
                Snapshot snapshot = new Snapshot(f, p, cl, byUuid, Instant.now());
                cache.put(player, snapshot);
                return snapshot;
            });
        });
    }
}
