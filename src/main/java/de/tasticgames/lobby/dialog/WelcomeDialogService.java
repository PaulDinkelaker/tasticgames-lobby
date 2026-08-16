package de.tasticgames.lobby.dialog;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.player.TasticPlayer;
import io.papermc.paper.dialog.Dialog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Short first-join welcome (not a tutorial). Completes the backend onboarding and stores
 * {@code welcome.seen} in the lobby preferences so it is shown once.
 */
public final class WelcomeDialogService {

    private final TasticCoreApi coreApi;
    private final LobbyApiService api;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final Consumer<Player> openGateway;
    private final Logger logger;
    private final Set<UUID> shown = ConcurrentHashMap.newKeySet();

    public WelcomeDialogService(TasticCoreApi coreApi, LobbyApiService api, LobbyMessages messages, DialogSupport dialogs,
                                MainThread mainThread, Consumer<Player> openGateway, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.api = Objects.requireNonNull(api);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.openGateway = Objects.requireNonNull(openGateway);
        this.logger = Objects.requireNonNull(logger);
    }

    public void openIfNeeded(Player player, TasticPlayer tasticPlayer) {
        if (!shown.add(player.getUniqueId())) {
            return;
        }
        // complete backend onboarding (LANGUAGE_SELECTION -> COMPLETED) – best effort, idempotent
        coreApi.playerOnboardingService().complete(player.getUniqueId()).whenComplete((o, t) -> {
            if (t != null) {
                logger.fine("Onboarding completion pending for " + player.getName() + ": " + t.getMessage());
            }
        });
        if (!api.enabled()) {
            open(player);
            return;
        }
        api.call("preferences", c -> c.lobby().preferences(player.getUniqueId())).whenComplete((prefs, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable == null && "true".equals(prefs.preferences().get("welcome.seen"))) {
                return;
            }
            open(player);
        }));
    }

    public void open(Player player) {
        var lang = messages.languageOf(player);
        Dialog dialog = dialogs.menu(messages.get(lang, "lobby.welcome.title", Map.of()),
                List.of(messages.get(lang, "lobby.welcome.line1", Map.of("player", player.getName())),
                        messages.get(lang, "lobby.welcome.line2", Map.of()),
                        messages.get(lang, "lobby.welcome.line3", Map.of()),
                        messages.get(lang, "lobby.welcome.line4", Map.of())),
                List.of(dialogs.button(player, messages.get(lang, "lobby.welcome.explore", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::markSeen),
                        dialogs.button(player, messages.get(lang, "lobby.welcome.gateway", Map.of()), null, DialogSupport.BUTTON_WIDTH, p -> {
                            markSeen(p);
                            openGateway.accept(p);
                        })),
                dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true);
        dialogs.show(player, dialog);
    }

    private void markSeen(Player player) {
        if (api.enabled()) {
            api.call("preferences.update", c -> c.lobby().updatePreference(player.getUniqueId(), "welcome.seen", "true"));
        }
    }

    public void forget(UUID uuid) {
        shown.remove(uuid);
    }
}
