package de.tasticgames.lobby.dialog;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.player.TasticPlayer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Onboarding language selection (blocking dialog, double-click safe, retry on failure).
 */
public final class LanguageDialogService {

    private final TasticCoreApi coreApi;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final Logger logger;
    private final Set<UUID> saving = ConcurrentHashMap.newKeySet();

    public LanguageDialogService(TasticCoreApi coreApi, LobbyMessages messages, DialogSupport dialogs, MainThread mainThread, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
    }

    public void open(Player player) {
        SupportedLanguage display = coreApi.playerManager().find(player.getUniqueId())
                .map(coreApi.localizationService()::languageOf).orElse(SupportedLanguage.ENGLISH);
        List<ActionButton> buttons = new ArrayList<>();
        for (SupportedLanguage language : SupportedLanguage.values()) {
            buttons.add(dialogs.button(player, Component.text(language.displayName(), NamedTextColor.YELLOW), null, DialogSupport.BUTTON_WIDTH,
                    clicked -> select(clicked, language)));
        }
        Dialog dialog = dialogs.menu(messages.get(display, "onboarding.language.title", java.util.Map.of()),
                List.of(messages.get(display, "onboarding.language.description", java.util.Map.of()),
                        messages.get(display, "onboarding.language.change-later", java.util.Map.of())),
                buttons, dialogs.close(messages.get(display, "common.close", java.util.Map.of())), 1, false);
        dialogs.show(player, dialog);
    }

    /** Also used by the settings dialog for a later language change. */
    public void select(Player player, SupportedLanguage language) {
        UUID uuid = player.getUniqueId();
        if (!saving.add(uuid)) {
            return;
        }
        TasticPlayer tasticPlayer = coreApi.playerManager().find(uuid).orElse(null);
        if (tasticPlayer == null) {
            saving.remove(uuid);
            open(player);
            return;
        }
        coreApi.playerLanguageUpdateDispatcher().update(tasticPlayer, language).whenComplete((onboarding, throwable) ->
                mainThread.run(() -> {
                    saving.remove(uuid);
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        logger.warning("Language selection failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                        messages.send(player, "onboarding.language.save-failed");
                        open(player);
                        return;
                    }
                    player.closeDialog();
                    // TasticPlayerLanguageChangedEvent triggers initialization; if the language did not change (same as before)
                    // the event is not fired, so initialize explicitly via the onboarding completion path.
                }));
    }
}
