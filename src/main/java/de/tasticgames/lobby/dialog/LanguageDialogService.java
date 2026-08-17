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
    private volatile java.util.function.BiConsumer<Player, TasticPlayer> onSelected = (p, t) -> { };

    /**
     * Continuation after a successful selection (also when the language did not change and Core
     * therefore fires no TasticPlayerLanguageChangedEvent): the bootstrap wires the lobby
     * initialization + welcome flow here.
     */
    public void setOnSelected(java.util.function.BiConsumer<Player, TasticPlayer> hook) {
        this.onSelected = Objects.requireNonNull(hook);
    }

    public LanguageDialogService(TasticCoreApi coreApi, LobbyMessages messages, DialogSupport dialogs, MainThread mainThread, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.logger = Objects.requireNonNull(logger);
    }

    /** Onboarding variant: cannot be escaped, selecting continues the join flow. */
    public void open(Player player) {
        open(player, false);
    }

    /**
     * @param closable true for the /lang and settings variant (escapable, has a close button)
     */
    public void open(Player player, boolean closable) {
        SupportedLanguage display = coreApi.playerManager().find(player.getUniqueId())
                .map(coreApi.localizationService()::languageOf).orElse(SupportedLanguage.ENGLISH);
        List<ActionButton> buttons = new ArrayList<>();
        for (SupportedLanguage language : SupportedLanguage.values()) {
            buttons.add(dialogs.button(player, Component.text(language.displayName(), NamedTextColor.YELLOW), null, DialogSupport.BUTTON_WIDTH,
                    clicked -> select(clicked, language, null, () -> open(clicked, closable))));
        }
        Dialog dialog = dialogs.menu(messages.get(display, "onboarding.language.title", java.util.Map.of()),
                List.of(messages.get(display, "onboarding.language.description", java.util.Map.of()),
                        messages.get(display, "onboarding.language.change-later", java.util.Map.of())),
                buttons, closable ? dialogs.close(messages.get(display, "common.close", java.util.Map.of())) : null, 1, closable);
        dialogs.show(player, dialog);
    }

    /** Selection used by onboarding and /lang: on failure the (closable or blocking) dialog reopens. */
    public void select(Player player, SupportedLanguage language) {
        select(player, language, null, () -> open(player, false));
    }

    /**
     * Persists the language through TasticCore. Success: the dialog is closed <em>before</em> Core
     * dispatches the language event (which may open follow-up dialogs), the continuation hook and
     * {@code onSuccess} run. Failure: feedback + {@code onFailure}.
     */
    public void select(Player player, SupportedLanguage language, Runnable onSuccess, Runnable onFailure) {
        UUID uuid = player.getUniqueId();
        if (!saving.add(uuid)) {
            return;
        }
        TasticPlayer tasticPlayer = coreApi.playerManager().find(uuid).orElse(null);
        if (tasticPlayer == null) {
            saving.remove(uuid);
            messages.send(player, "common.error");
            if (onFailure != null) onFailure.run();
            return;
        }
        player.closeDialog();
        coreApi.playerLanguageUpdateDispatcher().update(tasticPlayer, language).whenComplete((onboarding, throwable) ->
                mainThread.run(() -> {
                    saving.remove(uuid);
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        logger.warning("Language selection failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                        messages.send(player, "onboarding.language.save-failed");
                        if (onFailure != null) onFailure.run();
                        return;
                    }
                    messages.send(player, "language.changed", java.util.Map.of("language", language.displayName()));
                    try {
                        onSelected.accept(player, tasticPlayer);
                    } catch (RuntimeException e) {
                        logger.warning("Language continuation failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(e));
                    }
                    if (onSuccess != null) onSuccess.run();
                }));
    }

    /** Current language of the player (English when unknown). */
    public SupportedLanguage current(Player player) {
        return coreApi.playerManager().find(player.getUniqueId()).map(coreApi.localizationService()::languageOf).orElse(SupportedLanguage.ENGLISH);
    }
}
