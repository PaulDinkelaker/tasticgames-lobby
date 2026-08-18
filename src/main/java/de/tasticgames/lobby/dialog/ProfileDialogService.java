package de.tasticgames.lobby.dialog;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.cosmetic.CosmeticCategory;
import de.tasticgames.lobby.cosmetic.CosmeticDefinition;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.social.SocialActionService;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Player profiles (own + others): identity, rank, clan, party, cookie prestige, title/frame/background.
 */
public final class ProfileDialogService {

    public record CookieSummary(int prestige, String lifetimeCookies, boolean available) {
    }

    private final TasticCoreApi coreApi;
    private final SocialSnapshotService social;
    private final SocialActionService socialActions;
    private final CosmeticService cosmetics;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private volatile Function<UUID, java.util.concurrent.CompletableFuture<CookieSummary>> cookieSummary =
            uuid -> java.util.concurrent.CompletableFuture.completedFuture(new CookieSummary(0, "0", false));
    private volatile Function<Player, String> rankResolver = p -> "Player";
    private volatile java.util.function.Consumer<Player> cosmeticsOpener = p -> { };
    private volatile java.util.function.BiFunction<Player, UUID, java.util.Optional<Component>> passLine = (viewer, target) -> java.util.Optional.empty();

    public ProfileDialogService(TasticCoreApi coreApi, SocialSnapshotService social, SocialActionService socialActions, CosmeticService cosmetics,
                                LobbyMessages messages, DialogSupport dialogs, MainThread mainThread, LobbyTelemetryService telemetry, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.social = Objects.requireNonNull(social);
        this.socialActions = Objects.requireNonNull(socialActions);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    public void setCookieSummary(Function<UUID, java.util.concurrent.CompletableFuture<CookieSummary>> supplier) {
        this.cookieSummary = Objects.requireNonNull(supplier);
    }

    public void setRankResolver(Function<Player, String> resolver) {
        this.rankResolver = Objects.requireNonNull(resolver);
    }

    public void setCosmeticsOpener(java.util.function.Consumer<Player> opener) {
        this.cosmeticsOpener = Objects.requireNonNull(opener);
    }

    /** Season pass line of the profile (empty when the pass is disabled). */
    public void setPassLine(java.util.function.BiFunction<Player, UUID, java.util.Optional<Component>> resolver) {
        this.passLine = Objects.requireNonNull(resolver);
    }

    public void openOwn(Player player) {
        open(player, player.getUniqueId(), player.getName());
    }

    /** Opens the profile of another player (by uuid/name); relationship actions depend on the viewer's snapshot. */
    public void open(Player viewer, UUID target, String targetName) {
        telemetry.event("lobby.profile_open", viewer.getUniqueId(), Map.of("target", target));
        boolean own = viewer.getUniqueId().equals(target);
        SupportedLanguage lang = messages.languageOf(viewer);
        social.load(viewer.getUniqueId(), false).exceptionally(t -> null).thenCombine(cookieSummary.apply(target).exceptionally(t -> new CookieSummary(0, "0", false)),
                (snapshot, cookie) -> new Object[]{snapshot, cookie}).whenComplete((pair, throwable) -> mainThread.run(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            SocialSnapshotService.Snapshot snapshot = (SocialSnapshotService.Snapshot) pair[0];
            CookieSummary cookie = (CookieSummary) pair[1];
            List<Component> body = new ArrayList<>();
            String title = cosmetics.equipped(target, CosmeticCategory.TITLE).map(d -> messages.raw(lang, d.nameKey())).orElse("");
            String hat = cosmetics.equipped(target, CosmeticCategory.HAT).map(d -> messages.raw(lang, d.nameKey())).orElse(messages.raw(lang, "lobby.hud.none"));
            String back = cosmetics.equipped(target, CosmeticCategory.BACK_ITEM).map(d -> messages.raw(lang, d.nameKey())).orElse(messages.raw(lang, "lobby.hud.none"));
            Player targetOnline = Bukkit.getPlayer(target);
            body.add(messages.get(lang, "lobby.profile.name", Map.of("player", targetName, "title", messages.mini(title))));
            body.add(messages.get(lang, "lobby.profile.rank", Map.of("rank", targetOnline != null ? rankResolver.apply(targetOnline) : "-")));
            if (own && snapshot != null) {
                body.add(messages.get(lang, "lobby.profile.clan", Map.of("clan", snapshot.clan() == null ? messages.raw(lang, "lobby.hud.none") : snapshot.clan().name())));
                body.add(messages.get(lang, "lobby.profile.party", Map.of("size", snapshot.party() == null ? 0 : snapshot.party().members().size())));
                body.add(messages.get(lang, "lobby.profile.friends", Map.of("count", snapshot.friends() == null ? 0 : snapshot.friends().friends().size())));
            }
            body.add(cookie.available()
                    ? messages.get(lang, "lobby.profile.cookie", Map.of("prestige", cookie.prestige(), "lifetime", cookie.lifetimeCookies()))
                    : messages.get(lang, "lobby.profile.cookie_unavailable", Map.of()));
            passLine.apply(viewer, target).ifPresent(body::add);
            body.add(messages.get(lang, "lobby.profile.cosmetics", Map.of("hat", hat, "back", back)));
            var tastic = coreApi.playerManager().find(target).orElse(null);
            if (tastic != null) {
                body.add(messages.get(lang, "lobby.profile.joined", Map.of("date", java.time.LocalDate.ofInstant(tastic.firstSeenAt(), java.time.ZoneId.systemDefault()))));
            }
            List<ActionButton> buttons = new ArrayList<>();
            if (own) {
                buttons.add(dialogs.button(viewer, messages.get(lang, "lobby.profile.open_cosmetics", Map.of()), cosmeticsOpener::accept));
            } else if (snapshot != null) {
                boolean friend = snapshot.friendUuids().contains(target);
                boolean partyLeader = snapshot.party() != null && viewer.getUniqueId().equals(snapshot.party().leaderUuid());
                boolean clanOfficer = snapshot.clan() != null && snapshot.clan().members().stream()
                        .anyMatch(m -> m.minecraftUuid().equals(viewer.getUniqueId()) && m.role() != de.tasticgames.client.dto.social.ClanRoleResponse.MEMBER);
                if (friend) {
                    buttons.add(dialogs.button(viewer, messages.get(lang, "lobby.profile.remove_friend", Map.of()), p -> socialActions.removeFriend(p, target, targetName)));
                } else {
                    buttons.add(dialogs.button(viewer, messages.get(lang, "lobby.profile.add_friend", Map.of()), p -> socialActions.sendFriendRequest(p, target, targetName)));
                }
                if (partyLeader || snapshot.party() == null) {
                    buttons.add(dialogs.button(viewer, messages.get(lang, "lobby.profile.party_invite", Map.of()), p -> socialActions.partyInvite(p, target, targetName)));
                }
                if (clanOfficer) {
                    buttons.add(dialogs.button(viewer, messages.get(lang, "lobby.profile.clan_invite", Map.of()), p -> socialActions.clanInvite(p, target, targetName)));
                }
            }
            dialogs.show(viewer, dialogs.menu(messages.get(lang, own ? "lobby.profile.title_own" : "lobby.profile.title_other", Map.of("player", targetName)),
                    body, buttons, dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        }));
    }
}
