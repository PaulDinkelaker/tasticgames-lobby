package de.tasticgames.lobby.dialog;

import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.lobby.gateway.GatewayService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Gateway dialog: game modes with live availability, party awareness (leader may take the party).
 */
public final class GatewayDialogService {

    private final GatewayService gateway;
    private final SocialSnapshotService social;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;

    public GatewayDialogService(GatewayService gateway, SocialSnapshotService social, LobbyMessages messages, DialogSupport dialogs,
                                MainThread mainThread, LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.gateway = Objects.requireNonNull(gateway);
        this.social = Objects.requireNonNull(social);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    public void open(Player player) {
        telemetry.event("lobby.gateway_open", player.getUniqueId(), Map.of());
        SupportedLanguage lang = messages.languageOf(player);
        List<ActionButton> buttons = new ArrayList<>();
        List<Component> body = new ArrayList<>();
        body.add(messages.get(lang, "lobby.gateway.description", Map.of()));
        if (!gateway.available()) {
            body.add(messages.get(lang, "lobby.gateway.unavailable", Map.of()));
        } else if (gateway.networkMaintenance()) {
            body.add(messages.get(lang, "lobby.gateway.maintenance", Map.of()));
        }
        var snapshot = social.cached(player.getUniqueId()).orElse(null);
        boolean inParty = snapshot != null && snapshot.party() != null;
        boolean leader = inParty && player.getUniqueId().equals(snapshot.party().leaderUuid());
        for (GatewayService.ModeStatus mode : gateway.modes()) {
            String modeKey = "lobby.gateway.mode." + mode.type().name().toLowerCase(java.util.Locale.ROOT);
            Component status = mode.available()
                    ? messages.get(lang, "lobby.gateway.status.online", Map.of("players", mode.players()))
                    : messages.get(lang, mode.servers() == 0 ? "lobby.gateway.status.coming_soon" : "lobby.gateway.status.offline", Map.of());
            Component label = messages.get(lang, modeKey, Map.of()).append(Component.text(" ")).append(status);
            if (mode.available()) {
                buttons.add(dialogs.button(player, label, messages.get(lang, "lobby.gateway.tooltip.play", Map.of()), DialogSupport.WIDE_BUTTON_WIDTH,
                        p -> transfer(p, mode.type(), false)));
                if (leader) {
                    buttons.add(dialogs.button(player, messages.get(lang, "lobby.gateway.take_party", Map.of("mode", messages.get(lang, modeKey, Map.of()))),
                            messages.get(lang, "lobby.gateway.tooltip.party", Map.of("size", snapshot.party().members().size())), DialogSupport.WIDE_BUTTON_WIDTH,
                            p -> transfer(p, mode.type(), true)));
                }
            } else {
                buttons.add(dialogs.button(player, label, null, DialogSupport.WIDE_BUTTON_WIDTH, p -> {
                    sounds.error(p);
                    messages.send(p, "lobby.gateway.mode_unavailable");
                }));
            }
        }
        if (inParty && !leader) {
            body.add(messages.get(lang, "lobby.gateway.party_not_leader", Map.of()));
        }
        dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.gateway.title", Map.of()), body, buttons,
                dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
    }

    private void transfer(Player player, ServerTypeResponse type, boolean party) {
        messages.send(player, "lobby.gateway.connecting", Map.of("mode", messages.get(messages.languageOf(player),
                "lobby.gateway.mode." + type.name().toLowerCase(java.util.Locale.ROOT), Map.of())));
        gateway.requestTransfer(player, type, party).whenComplete((response, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                logger.warning("Gateway transfer request failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                messages.send(player, "lobby.gateway.failed");
                sounds.error(player);
                return;
            }
            if (!response.accepted()) {
                if ("COOLDOWN".equals(response.reason())) {
                    messages.send(player, "lobby.gateway.cooldown");
                } else {
                    messages.send(player, "lobby.gateway.failed");
                }
                sounds.error(player);
            }
        }));
    }
}
