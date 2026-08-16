package de.tasticgames.lobby.social;

import de.tasticgames.client.dto.social.ClanActionRequest;
import de.tasticgames.client.dto.social.ClanActionResponse;
import de.tasticgames.client.dto.social.FriendActionRequest;
import de.tasticgames.client.dto.social.FriendActionResponse;
import de.tasticgames.client.dto.social.PartyActionRequest;
import de.tasticgames.client.dto.social.PartyActionResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.service.Service;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Social mutations from the lobby UI: persisted through the API (same rules as the proxy
 * commands), targets notified through the proxy command bus, snapshot invalidated, feedback localized.
 */
public final class SocialActionService implements Service {

    private final LobbyApiService api;
    private final SocialSnapshotService snapshots;
    private final NetworkNotifier notifier;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final MainThread mainThread;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private volatile Consumer<Player> afterAction = p -> { };

    public SocialActionService(LobbyApiService api, SocialSnapshotService snapshots, NetworkNotifier notifier, LobbyMessages messages,
                               LobbySounds sounds, MainThread mainThread, LobbyTelemetryService telemetry, Logger logger) {
        this.api = Objects.requireNonNull(api);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.notifier = Objects.requireNonNull(notifier);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "social-action-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    /** Hook to re-render UI (visibility, HUD) after a change. */
    public void setAfterAction(Consumer<Player> hook) {
        this.afterAction = Objects.requireNonNull(hook);
    }

    // ------------------------------------------------------------------ friends

    public void sendFriendRequest(Player actor, UUID target, String targetName) {
        run(actor, api.call("friend.request", c -> c.social().sendFriendRequest(actor.getUniqueId(), new FriendActionRequest(target, UUID.randomUUID()))),
                response -> {
                    switch (response.outcome()) {
                        case REQUEST_SENT -> {
                            notifier.notify(target, "friend.request.received", Map.of("player", actor.getName()));
                            messages.send(actor, "lobby.social.friend.request_sent", Map.of("player", targetName));
                        }
                        case ACCEPTED_CROSSED -> {
                            notifier.notify(target, "friend.accepted.notify", Map.of("player", actor.getName()));
                            messages.send(actor, "lobby.social.friend.accepted", Map.of("player", targetName));
                        }
                        default -> messages.send(actor, "lobby.social.friend.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT), Map.of("player", targetName));
                    }
                });
    }

    public void acceptFriendRequest(Player actor, UUID from, String fromName) {
        run(actor, api.call("friend.accept", c -> c.social().acceptFriendRequest(actor.getUniqueId(), from)), response -> {
            if (response.outcome() == de.tasticgames.client.dto.social.FriendOutcomeResponse.ACCEPTED) {
                notifier.notify(from, "friend.accepted.notify", Map.of("player", actor.getName()));
                messages.send(actor, "lobby.social.friend.accepted", Map.of("player", fromName));
            } else {
                messages.send(actor, "lobby.social.friend.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT), Map.of("player", fromName));
            }
        });
    }

    public void denyFriendRequest(Player actor, UUID from, String fromName) {
        run(actor, api.call("friend.deny", c -> c.social().denyFriendRequest(actor.getUniqueId(), from)),
                response -> messages.send(actor, "lobby.social.friend.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT), Map.of("player", fromName)));
    }

    public void removeFriend(Player actor, UUID other, String otherName) {
        run(actor, api.call("friend.remove", c -> c.social().removeFriend(actor.getUniqueId(), other)), response -> {
            if (response.outcome() == de.tasticgames.client.dto.social.FriendOutcomeResponse.REMOVED) {
                notifier.notify(other, "friend.removed.notify", Map.of("player", actor.getName()));
            }
            messages.send(actor, "lobby.social.friend.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT), Map.of("player", otherName));
        });
    }

    // ------------------------------------------------------------------ party

    public void partyInvite(Player actor, UUID target, String targetName) {
        PartyActionRequest request = new PartyActionRequest(actor.getUniqueId(), target, null, UUID.randomUUID(), 8, 60);
        run(actor, api.call("party.invite", c -> c.social().partyInvite(request)), response -> {
            if (response.ok()) {
                notifier.notify(target, "party.invite.received", Map.of("player", actor.getName(), "seconds", 60));
                messages.send(actor, "lobby.social.party.invited", Map.of("player", targetName));
            } else {
                partyOutcome(actor, response, targetName);
            }
        });
    }

    public void partyAccept(Player actor, UUID partyId, UUID inviter, String inviterName) {
        PartyActionRequest request = new PartyActionRequest(actor.getUniqueId(), inviter, partyId, UUID.randomUUID(), 8, null);
        run(actor, api.call("party.accept", c -> c.social().partyAccept(request)), response -> {
            if (response.ok() && response.party() != null) {
                response.party().members().forEach(m -> {
                    if (!m.minecraftUuid().equals(actor.getUniqueId())) {
                        notifier.notify(m.minecraftUuid(), "party.joined.broadcast", Map.of("player", actor.getName()));
                    }
                });
                messages.send(actor, "lobby.social.party.joined", Map.of());
            } else {
                partyOutcome(actor, response, inviterName);
            }
        });
    }

    public void partyDeny(Player actor, UUID partyId, UUID inviter, String inviterName) {
        PartyActionRequest request = new PartyActionRequest(actor.getUniqueId(), inviter, partyId, UUID.randomUUID(), null, null);
        run(actor, api.call("party.deny", c -> c.social().partyDeny(request)), response -> {
            if (response.ok() && inviter != null) {
                notifier.notify(inviter, "party.invite.denied_notify", Map.of("player", actor.getName()));
            }
            partyOutcome(actor, response, inviterName);
        });
    }

    public void partyLeave(Player actor) {
        run(actor, api.call("party.leave", c -> c.social().partyLeave(simple(actor))), response -> {
            if (response.ok() && response.party() != null) {
                response.party().members().forEach(m -> notifier.notify(m.minecraftUuid(), "party.left.broadcast", Map.of("player", actor.getName())));
            }
            partyOutcome(actor, response, "");
        });
    }

    public void partyKick(Player actor, UUID target, String targetName) {
        PartyActionRequest request = new PartyActionRequest(actor.getUniqueId(), target, null, UUID.randomUUID(), null, null);
        run(actor, api.call("party.kick", c -> c.social().partyKick(request)), response -> {
            if (response.ok()) {
                notifier.notify(target, "party.kicked.notify", Map.of("player", actor.getName()));
            }
            partyOutcome(actor, response, targetName);
        });
    }

    public void partyPromote(Player actor, UUID target, String targetName) {
        PartyActionRequest request = new PartyActionRequest(actor.getUniqueId(), target, null, UUID.randomUUID(), null, null);
        run(actor, api.call("party.promote", c -> c.social().partyPromote(request)), response -> {
            if (response.ok() && response.party() != null) {
                response.party().members().forEach(m -> notifier.notify(m.minecraftUuid(), "party.promoted.broadcast", Map.of("player", targetName)));
            }
            partyOutcome(actor, response, targetName);
        });
    }

    public void partyDisband(Player actor) {
        run(actor, api.call("party.disband", c -> c.social().partyDisband(simple(actor))), response -> {
            if (response.ok() && response.party() != null) {
                response.party().members().forEach(m -> {
                    if (!m.minecraftUuid().equals(actor.getUniqueId())) {
                        notifier.notify(m.minecraftUuid(), "party.disbanded.broadcast", Map.of("player", actor.getName()));
                    }
                });
            }
            partyOutcome(actor, response, "");
        });
    }

    private void partyOutcome(Player actor, PartyActionResponse response, String targetName) {
        messages.send(actor, "lobby.social.party.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT), Map.of("player", targetName));
    }

    // ------------------------------------------------------------------ clan

    public void clanInvite(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), 50, 300);
        run(actor, api.call("clan.invite", c -> c.social().clanInvite(request)), response -> {
            if (response.ok()) {
                notifier.notify(target, "clan.invite.received", Map.of("player", actor.getName(), "clan", response.clan() == null ? "?" : response.clan().name()));
            }
            clanOutcome(actor, response, targetName);
        });
    }

    public void clanAcceptInvite(Player actor, UUID clanId) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), null, clanId, null, UUID.randomUUID(), 50, null);
        run(actor, api.call("clan.accept", c -> c.social().clanAcceptInvite(request)), response -> {
            if (response.ok() && response.clan() != null) {
                response.clan().members().forEach(m -> {
                    if (!m.minecraftUuid().equals(actor.getUniqueId())) {
                        notifier.notify(m.minecraftUuid(), "clan.joined.broadcast", Map.of("player", actor.getName()));
                    }
                });
            }
            clanOutcome(actor, response, "");
        });
    }

    public void clanDenyInvite(Player actor, UUID clanId) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), null, clanId, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.deny", c -> c.social().clanDenyInvite(request)), response -> clanOutcome(actor, response, ""));
    }

    public void clanAcceptRequest(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), 50, null);
        run(actor, api.call("clan.acceptRequest", c -> c.social().clanAcceptRequest(request)), response -> {
            if (response.ok()) {
                notifier.notify(target, "clan.request.accepted_notify", Map.of("clan", response.clan() == null ? "?" : response.clan().name()));
            }
            clanOutcome(actor, response, targetName);
        });
    }

    public void clanDenyRequest(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.denyRequest", c -> c.social().clanDenyRequest(request)), response -> clanOutcome(actor, response, targetName));
    }

    public void clanKick(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.kick", c -> c.social().clanKick(request)), response -> {
            if (response.ok()) {
                notifier.notify(target, "clan.kicked.notify", Map.of("player", actor.getName()));
            }
            clanOutcome(actor, response, targetName);
        });
    }

    public void clanPromote(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.promote", c -> c.social().clanPromote(request)), response -> clanOutcome(actor, response, targetName));
    }

    public void clanDemote(Player actor, UUID target, String targetName) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), target, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.demote", c -> c.social().clanDemote(request)), response -> clanOutcome(actor, response, targetName));
    }

    public void clanLeave(Player actor) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), null, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.leave", c -> c.social().clanLeave(request)), response -> clanOutcome(actor, response, ""));
    }

    public void clanDisband(Player actor) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), null, null, null, UUID.randomUUID(), null, null);
        run(actor, api.call("clan.disband", c -> c.social().clanDisband(request)), response -> {
            if (response.ok() && response.clan() != null) {
                response.clan().members().forEach(m -> {
                    if (!m.minecraftUuid().equals(actor.getUniqueId())) {
                        notifier.notify(m.minecraftUuid(), "clan.disbanded.broadcast", Map.of("player", actor.getName()));
                    }
                });
            }
            clanOutcome(actor, response, "");
        });
    }

    public void clanCreate(Player actor, String name) {
        ClanActionRequest request = new ClanActionRequest(actor.getUniqueId(), null, null, name, UUID.randomUUID(), 50, null);
        run(actor, api.call("clan.create", c -> c.social().clanCreate(request)), response -> clanOutcome(actor, response, name));
    }

    private void clanOutcome(Player actor, ClanActionResponse response, String targetName) {
        messages.send(actor, "lobby.social.clan.outcome." + response.outcome().name().toLowerCase(java.util.Locale.ROOT),
                Map.of("player", targetName, "clan", response.clan() == null ? "" : response.clan().name()));
    }

    // ------------------------------------------------------------------ plumbing

    private static PartyActionRequest simple(Player actor) {
        return new PartyActionRequest(actor.getUniqueId(), null, null, UUID.randomUUID(), null, null);
    }

    private <T> void run(Player actor, CompletableFuture<T> future, Consumer<T> onResult) {
        future.whenComplete((result, throwable) -> mainThread.run(() -> {
            if (!actor.isOnline()) {
                return;
            }
            snapshots.invalidate(actor.getUniqueId());
            if (throwable != null) {
                Throwable cause = LobbyThrowables.unwrap(throwable);
                if (cause instanceof LobbyApiService.ApiUnavailableException) {
                    messages.send(actor, "lobby.social.unavailable");
                } else {
                    logger.warning("Social action failed for " + actor.getName() + ": " + LobbyThrowables.rootMessage(cause));
                    messages.send(actor, "common.error");
                }
                sounds.error(actor);
                return;
            }
            sounds.success(actor);
            onResult.accept(result);
            telemetry.event("lobby.social_action", actor.getUniqueId(), Map.of());
            afterAction.accept(actor);
        }));
    }
}
