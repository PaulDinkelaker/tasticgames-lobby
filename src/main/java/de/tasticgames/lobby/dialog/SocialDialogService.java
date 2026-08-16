package de.tasticgames.lobby.dialog;

import de.tasticgames.client.dto.social.ClanInviteResponse;
import de.tasticgames.client.dto.social.ClanJoinRequestResponse;
import de.tasticgames.client.dto.social.ClanMemberResponse;
import de.tasticgames.client.dto.social.ClanRoleResponse;
import de.tasticgames.client.dto.social.FriendRequestResponse;
import de.tasticgames.client.dto.social.FriendResponse;
import de.tasticgames.client.dto.social.PartyInviteResponse;
import de.tasticgames.client.dto.social.PartyMemberResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.social.SocialActionService;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Social hub: friends / party / clan views with actions and navigation (no dead ends).
 */
public final class SocialDialogService {

    private final LobbyApiService api;
    private final SocialSnapshotService social;
    private final SocialActionService actions;
    private final ProfileDialogService profiles;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;

    public SocialDialogService(LobbyApiService api, SocialSnapshotService social, SocialActionService actions, ProfileDialogService profiles,
                               LobbyMessages messages, DialogSupport dialogs, MainThread mainThread, LobbyTelemetryService telemetry, Logger logger) {
        this.api = Objects.requireNonNull(api);
        this.social = Objects.requireNonNull(social);
        this.actions = Objects.requireNonNull(actions);
        this.profiles = Objects.requireNonNull(profiles);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    public void openHub(Player player) {
        telemetry.event("lobby.social_open", player.getUniqueId(), Map.of());
        SupportedLanguage lang = messages.languageOf(player);
        if (!social.available()) {
            dialogs.show(player, dialogs.notice(messages.get(lang, "lobby.social.title", Map.of()),
                    List.of(messages.get(lang, "lobby.social.unavailable", Map.of())), dialogs.close(messages.get(lang, "common.close", Map.of()))));
            return;
        }
        withSnapshot(player, true, snapshot -> {
            long online = snapshot.friends().friends().stream().filter(f -> snapshot.online(f.minecraftUuid())).count();
            List<Component> body = List.of(
                    messages.get(lang, "lobby.social.summary.friends", Map.of("online", online, "total", snapshot.friends().friends().size(),
                            "pending", snapshot.friends().incomingRequests().size())),
                    messages.get(lang, "lobby.social.summary.party", Map.of("size", snapshot.party() == null ? 0 : snapshot.party().members().size())),
                    messages.get(lang, "lobby.social.summary.clan", Map.of("clan", snapshot.clan() == null ? messages.raw(lang, "lobby.hud.none") : snapshot.clan().name())));
            List<ActionButton> buttons = List.of(
                    dialogs.button(player, messages.get(lang, "lobby.social.friends", Map.of()), this::openFriends),
                    dialogs.button(player, messages.get(lang, "lobby.social.party", Map.of()), this::openParty),
                    dialogs.button(player, messages.get(lang, "lobby.social.clan", Map.of()), this::openClan));
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    // ------------------------------------------------------------------ friends

    public void openFriends(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        withSnapshot(player, false, snapshot -> {
            List<ActionButton> buttons = new ArrayList<>();
            List<FriendResponse> friends = new ArrayList<>(snapshot.friends().friends());
            friends.sort((a, b) -> {
                boolean oa = snapshot.online(a.minecraftUuid());
                boolean ob = snapshot.online(b.minecraftUuid());
                if (oa != ob) return oa ? -1 : 1;
                return a.name().compareToIgnoreCase(b.name());
            });
            for (FriendResponse friend : friends) {
                boolean online = snapshot.online(friend.minecraftUuid());
                Component label = messages.get(lang, online ? "lobby.social.friend.online" : "lobby.social.friend.offline",
                        Map.of("player", friend.name(), "server", online ? String.valueOf(snapshot.serverTypeOf(friend.minecraftUuid())) : ""));
                buttons.add(dialogs.button(player, label, null, DialogSupport.WIDE_BUTTON_WIDTH, p -> profiles.open(p, friend.minecraftUuid(), friend.name())));
            }
            for (FriendRequestResponse request : snapshot.friends().incomingRequests()) {
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.friend.accept", Map.of("player", request.fromName())), null, DialogSupport.WIDE_BUTTON_WIDTH,
                        p -> { actions.acceptFriendRequest(p, request.fromUuid(), request.fromName()); reopenLater(p, this::openFriends); }));
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.friend.deny", Map.of("player", request.fromName())), null, DialogSupport.WIDE_BUTTON_WIDTH,
                        p -> { actions.denyFriendRequest(p, request.fromUuid(), request.fromName()); reopenLater(p, this::openFriends); }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.friend.add", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openAddFriend));
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openHub));
            List<Component> body = friends.isEmpty() ? List.of(messages.get(lang, "lobby.social.friend.empty", Map.of())) : List.of();
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.friends", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    private void openAddFriend(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        List<DialogInput> inputs = List.of(DialogInput.text("name", messages.get(lang, "lobby.social.friend.add_prompt", Map.of())).maxLength(16).build());
        ActionButton submit = dialogs.formButton(player, messages.get(lang, "common.confirm", Map.of()), DialogSupport.BUTTON_WIDTH, (p, view) -> {
            String name = view.getText("name");
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
                messages.send(p, "common.player-not-found", Map.of("player", String.valueOf(name)));
                return;
            }
            resolve(p, name, (uuid, resolvedName) -> actions.sendFriendRequest(p, uuid, resolvedName));
        });
        dialogs.show(player, dialogs.form(messages.get(lang, "lobby.social.friend.add", Map.of()), List.of(), inputs, submit,
                dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openFriends)));
    }

    // ------------------------------------------------------------------ party

    public void openParty(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        withSnapshot(player, false, snapshot -> {
            List<ActionButton> buttons = new ArrayList<>();
            List<Component> body = new ArrayList<>();
            if (snapshot.party() == null) {
                body.add(messages.get(lang, "lobby.social.party.none", Map.of()));
                api.call("party.invites", c -> c.social().partyInvitesOf(player.getUniqueId())).whenComplete((invites, throwable) -> mainThread.run(() -> {
                    if (throwable == null) {
                        for (PartyInviteResponse invite : invites) {
                            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.accept_invite", Map.of("player", invite.invitedByName())), null,
                                    DialogSupport.WIDE_BUTTON_WIDTH, p -> { actions.partyAccept(p, invite.partyId(), invite.invitedByUuid(), invite.invitedByName()); reopenLater(p, this::openParty); }));
                            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.deny_invite", Map.of("player", invite.invitedByName())), null,
                                    DialogSupport.WIDE_BUTTON_WIDTH, p -> { actions.partyDeny(p, invite.partyId(), invite.invitedByUuid(), invite.invitedByName()); reopenLater(p, this::openParty); }));
                        }
                    }
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.invite", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openPartyInvite));
                    buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openHub));
                    dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.party", Map.of()), body, buttons,
                            dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
                }));
                return;
            }
            boolean leader = player.getUniqueId().equals(snapshot.party().leaderUuid());
            body.add(messages.get(lang, "lobby.social.party.header", Map.of("size", snapshot.party().members().size())));
            for (PartyMemberResponse member : snapshot.party().members()) {
                boolean online = snapshot.online(member.minecraftUuid());
                body.add(messages.get(lang, member.leader() ? "lobby.social.party.member_leader" : "lobby.social.party.member",
                        Map.of("player", member.name(), "server", online ? String.valueOf(snapshot.serverTypeOf(member.minecraftUuid())) : messages.raw(lang, "lobby.social.offline"))));
                if (leader && !member.leader()) {
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.kick", Map.of("player", member.name())), null, DialogSupport.BUTTON_WIDTH,
                            p -> { actions.partyKick(p, member.minecraftUuid(), member.name()); reopenLater(p, this::openParty); }));
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.promote", Map.of("player", member.name())), null, DialogSupport.BUTTON_WIDTH,
                            p -> { actions.partyPromote(p, member.minecraftUuid(), member.name()); reopenLater(p, this::openParty); }));
                }
            }
            if (leader) {
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.invite", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openPartyInvite));
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.disband", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                        p -> { actions.partyDisband(p); reopenLater(p, this::openParty); }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.party.leave", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                    p -> { actions.partyLeave(p); reopenLater(p, this::openParty); }));
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openHub));
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.party", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
        });
    }

    private void openPartyInvite(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        List<DialogInput> inputs = List.of(DialogInput.text("name", messages.get(lang, "lobby.social.party.invite_prompt", Map.of())).maxLength(16).build());
        ActionButton submit = dialogs.formButton(player, messages.get(lang, "common.confirm", Map.of()), DialogSupport.BUTTON_WIDTH, (p, view) -> {
            String name = view.getText("name");
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
                messages.send(p, "common.player-not-found", Map.of("player", String.valueOf(name)));
                return;
            }
            resolve(p, name, (uuid, resolvedName) -> actions.partyInvite(p, uuid, resolvedName));
        });
        dialogs.show(player, dialogs.form(messages.get(lang, "lobby.social.party.invite", Map.of()), List.of(), inputs, submit,
                dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openParty)));
    }

    // ------------------------------------------------------------------ clan

    public void openClan(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        withSnapshot(player, false, snapshot -> {
            List<ActionButton> buttons = new ArrayList<>();
            List<Component> body = new ArrayList<>();
            if (snapshot.clan() == null) {
                body.add(messages.get(lang, "lobby.social.clan.none", Map.of()));
                api.call("clan.invites", c -> c.social().clanInvitesOf(player.getUniqueId())).whenComplete((invites, throwable) -> mainThread.run(() -> {
                    if (throwable == null) {
                        for (ClanInviteResponse invite : invites) {
                            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.accept_invite", Map.of("clan", invite.clanName())), null,
                                    DialogSupport.WIDE_BUTTON_WIDTH, p -> { actions.clanAcceptInvite(p, invite.clanId()); reopenLater(p, this::openClan); }));
                            buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.deny_invite", Map.of("clan", invite.clanName())), null,
                                    DialogSupport.WIDE_BUTTON_WIDTH, p -> { actions.clanDenyInvite(p, invite.clanId()); reopenLater(p, this::openClan); }));
                        }
                    }
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.create", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openClanCreate));
                    buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openHub));
                    dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.clan", Map.of()), body, buttons,
                            dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
                }));
                return;
            }
            var clan = snapshot.clan();
            ClanRoleResponse myRole = clan.members().stream().filter(m -> m.minecraftUuid().equals(player.getUniqueId())).map(ClanMemberResponse::role).findFirst().orElse(ClanRoleResponse.MEMBER);
            boolean officer = myRole != ClanRoleResponse.MEMBER;
            boolean owner = myRole == ClanRoleResponse.OWNER;
            long online = clan.members().stream().filter(m -> snapshot.online(m.minecraftUuid())).count();
            body.add(messages.get(lang, "lobby.social.clan.header", Map.of("clan", clan.name(), "members", clan.members().size(), "online", online)));
            for (ClanMemberResponse member : clan.members()) {
                body.add(messages.get(lang, "lobby.social.clan.member", Map.of("player", member.name(), "role", member.role(),
                        "status", snapshot.online(member.minecraftUuid()) ? messages.raw(lang, "lobby.social.online") : messages.raw(lang, "lobby.social.offline"))));
            }
            if (officer) {
                for (ClanJoinRequestResponse request : clan.joinRequests()) {
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.accept_request", Map.of("player", request.playerName())), null, DialogSupport.BUTTON_WIDTH,
                            p -> { actions.clanAcceptRequest(p, request.playerUuid(), request.playerName()); reopenLater(p, this::openClan); }));
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.deny_request", Map.of("player", request.playerName())), null, DialogSupport.BUTTON_WIDTH,
                            p -> { actions.clanDenyRequest(p, request.playerUuid(), request.playerName()); reopenLater(p, this::openClan); }));
                }
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.invite", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openClanInvite));
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.manage", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openClanManage));
            }
            if (owner) {
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.disband", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                        p -> confirm(p, "lobby.social.clan.disband_confirm", () -> { actions.clanDisband(p); reopenLater(p, this::openClan); }, this::openClan)));
            } else {
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.leave", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                        p -> { actions.clanLeave(p); reopenLater(p, this::openClan); }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openHub));
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.clan", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
        });
    }

    private void openClanManage(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        withSnapshot(player, false, snapshot -> {
            if (snapshot.clan() == null) {
                openClan(player);
                return;
            }
            List<ActionButton> buttons = new ArrayList<>();
            for (ClanMemberResponse member : snapshot.clan().members()) {
                if (member.minecraftUuid().equals(player.getUniqueId()) || member.role() == ClanRoleResponse.OWNER) continue;
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.promote", Map.of("player", member.name())), null, DialogSupport.BUTTON_WIDTH,
                        p -> { actions.clanPromote(p, member.minecraftUuid(), member.name()); reopenLater(p, this::openClanManage); }));
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.demote", Map.of("player", member.name())), null, DialogSupport.BUTTON_WIDTH,
                        p -> { actions.clanDemote(p, member.minecraftUuid(), member.name()); reopenLater(p, this::openClanManage); }));
                buttons.add(dialogs.button(player, messages.get(lang, "lobby.social.clan.kick", Map.of("player", member.name())), null, DialogSupport.BUTTON_WIDTH,
                        p -> { actions.clanKick(p, member.minecraftUuid(), member.name()); reopenLater(p, this::openClanManage); }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openClan));
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.social.clan.manage", Map.of()), List.of(), buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 3, true));
        });
    }

    private void openClanInvite(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        List<DialogInput> inputs = List.of(DialogInput.text("name", messages.get(lang, "lobby.social.clan.invite_prompt", Map.of())).maxLength(16).build());
        ActionButton submit = dialogs.formButton(player, messages.get(lang, "common.confirm", Map.of()), DialogSupport.BUTTON_WIDTH, (p, view) -> {
            String name = view.getText("name");
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
                messages.send(p, "common.player-not-found", Map.of("player", String.valueOf(name)));
                return;
            }
            resolve(p, name, (uuid, resolvedName) -> actions.clanInvite(p, uuid, resolvedName));
        });
        dialogs.show(player, dialogs.form(messages.get(lang, "lobby.social.clan.invite", Map.of()), List.of(), inputs, submit,
                dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openClan)));
    }

    private void openClanCreate(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        List<DialogInput> inputs = List.of(DialogInput.text("name", messages.get(lang, "lobby.social.clan.create_prompt", Map.of())).maxLength(16).build());
        ActionButton submit = dialogs.formButton(player, messages.get(lang, "common.confirm", Map.of()), DialogSupport.BUTTON_WIDTH, (p, view) -> {
            String name = view.getText("name");
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
                messages.send(p, "lobby.social.clan.outcome.invalid_name");
                return;
            }
            actions.clanCreate(p, name);
            reopenLater(p, this::openClan);
        });
        dialogs.show(player, dialogs.form(messages.get(lang, "lobby.social.clan.create", Map.of()), List.of(), inputs, submit,
                dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openClan)));
    }

    // ------------------------------------------------------------------ helpers

    private void confirm(Player player, String bodyKey, Runnable yes, java.util.function.Consumer<Player> no) {
        SupportedLanguage lang = messages.languageOf(player);
        dialogs.show(player, dialogs.confirm(messages.get(lang, "common.confirm", Map.of()), List.of(messages.get(lang, bodyKey, Map.of())),
                dialogs.button(player, messages.get(lang, "common.yes", Map.of()), p -> yes.run()),
                dialogs.button(player, messages.get(lang, "common.no", Map.of()), no)));
    }

    private void resolve(Player player, String name, BiConsumer<UUID, String> then) {
        Player online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(online.getUniqueId(), online.getName());
            return;
        }
        api.call("player.byName", c -> c.network().findPlayerByName(name)).whenComplete((account, throwable) -> mainThread.run(() -> {
            if (throwable != null || account.isEmpty()) {
                messages.send(player, "common.player-not-found", Map.of("player", name));
                return;
            }
            then.accept(account.get().minecraftUuid(), account.get().currentName());
        }));
    }

    private void withSnapshot(Player player, boolean force, java.util.function.Consumer<SocialSnapshotService.Snapshot> consumer) {
        social.load(player.getUniqueId(), force).whenComplete((snapshot, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                messages.send(player, "lobby.social.unavailable");
                return;
            }
            consumer.accept(snapshot);
        }));
    }

    private void reopenLater(Player player, java.util.function.Consumer<Player> view) {
        mainThread.later(15L, () -> {
            if (player.isOnline()) {
                view.accept(player);
            }
        });
    }
}
