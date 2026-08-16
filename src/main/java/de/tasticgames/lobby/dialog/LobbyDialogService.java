package de.tasticgames.lobby.dialog;

import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.localization.TranslationKey;
import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.player.TasticPlayer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class LobbyDialogService {

    private static final TranslationKey LANGUAGE_TITLE =
            TranslationKey.of(
                    "onboarding.language.title"
            );

    private static final TranslationKey LANGUAGE_DESCRIPTION =
            TranslationKey.of(
                    "onboarding.language.description"
            );

    private static final TranslationKey LANGUAGE_CHANGE_LATER =
            TranslationKey.of(
                    "onboarding.language.change-later"
            );

    private static final TranslationKey LANGUAGE_SAVE_FAILED =
            TranslationKey.of(
                    "onboarding.language.save-failed"
            );

    private final TasticLobbyPlugin plugin;

    public LobbyDialogService(
            TasticLobbyPlugin plugin
    ) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );
    }

    public void openLanguageSelectionDialog(
            Player player
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        SupportedLanguage displayLanguage =
                resolveDisplayLanguage(
                        player
                );

        Dialog dialog =
                Dialog.create(
                        builder ->
                                builder
                                        .empty()
                                        .base(
                                                DialogBase.builder(
                                                                plugin.coreApi()
                                                                        .localizationService()
                                                                        .translate(
                                                                                displayLanguage,
                                                                                LANGUAGE_TITLE
                                                                        )
                                                        )
                                                        .body(
                                                                List.of(
                                                                        DialogBody.plainMessage(
                                                                                plugin.coreApi()
                                                                                        .localizationService()
                                                                                        .translate(
                                                                                                displayLanguage,
                                                                                                LANGUAGE_DESCRIPTION
                                                                                        )
                                                                        ),
                                                                        DialogBody.plainMessage(
                                                                                plugin.coreApi()
                                                                                        .localizationService()
                                                                                        .translate(
                                                                                                displayLanguage,
                                                                                                LANGUAGE_CHANGE_LATER
                                                                                        )
                                                                        )
                                                                )
                                                        )
                                                        .canCloseWithEscape(
                                                                false
                                                        )
                                                        .build()
                                        )
                                        .type(
                                                DialogType.multiAction(
                                                                List.of(
                                                                        createLanguageButton(
                                                                                player,
                                                                                SupportedLanguage.ENGLISH
                                                                        ),
                                                                        createLanguageButton(
                                                                                player,
                                                                                SupportedLanguage.GERMAN
                                                                        ),
                                                                        createLanguageButton(
                                                                                player,
                                                                                SupportedLanguage.HINDI
                                                                        )
                                                                )
                                                        )
                                                        .columns(
                                                                1
                                                        )
                                                        .build()
                                        )
                );

        player.showDialog(
                dialog
        );
    }

    private ActionButton createLanguageButton(
            Player player,
            SupportedLanguage language
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Objects.requireNonNull(
                language,
                "language"
        );

        UUID minecraftUuid =
                player.getUniqueId();

        return ActionButton.builder(
                        Component.text(
                                language.displayName(),
                                NamedTextColor.YELLOW
                        )
                )
                .width(
                        220
                )
                .action(
                        DialogAction.customClick(
                                (view, audience) -> {
                                    if (!(audience instanceof Player clickedPlayer)) {
                                        return;
                                    }

                                    if (!clickedPlayer
                                            .getUniqueId()
                                            .equals(
                                                    minecraftUuid
                                            )) {
                                        return;
                                    }

                                    handleLanguageSelection(
                                            clickedPlayer,
                                            language
                                    );
                                },
                                ClickCallback.Options.builder()
                                        .uses(
                                                1
                                        )
                                        .build()
                        )
                )
                .build();
    }

    private void handleLanguageSelection(
            Player player,
            SupportedLanguage language
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Objects.requireNonNull(
                language,
                "language"
        );

        UUID minecraftUuid =
                player.getUniqueId();

        TasticPlayer tasticPlayer;

        try {
            tasticPlayer =
                    plugin.coreApi()
                            .playerManager()
                            .require(
                                    minecraftUuid
                            );
        } catch (Exception exception) {
            plugin.getLogger().severe(
                    "Cannot update language for "
                            + player.getName()
                            + " ["
                            + minecraftUuid
                            + "]: "
                            + safeMessage(
                            exception
                    )
            );

            return;
        }

        plugin.coreApi()
                .playerLanguageUpdateDispatcher()
                .update(
                        tasticPlayer,
                        language
                )
                .whenComplete(
                        (onboarding, throwable) ->
                                plugin.getServer()
                                        .getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    if (throwable != null) {
                                                        handleLanguageSelectionFailure(
                                                                player,
                                                                language,
                                                                minecraftUuid,
                                                                unwrap(
                                                                        throwable
                                                                )
                                                        );

                                                        return;
                                                    }

                                                    if (!player.isOnline()) {
                                                        return;
                                                    }

                                                    player.closeDialog();

                                                    plugin.getLogger().info(
                                                            "Selected language "
                                                                    + language.code()
                                                                    + " for "
                                                                    + player.getName()
                                                                    + " ["
                                                                    + minecraftUuid
                                                                    + "]"
                                                    );
                                                }
                                        )
                );
    }

    private void handleLanguageSelectionFailure(
            Player player,
            SupportedLanguage attemptedLanguage,
            UUID minecraftUuid,
            Throwable cause
    ) {
        plugin.getLogger().severe(
                "Failed to save language selection for "
                        + player.getName()
                        + " ["
                        + minecraftUuid
                        + "]: "
                        + safeMessage(
                        cause
                )
        );

        if (!player.isOnline()) {
            return;
        }

        player.sendMessage(
                plugin.coreApi()
                        .localizationService()
                        .translate(
                                resolveDisplayLanguage(
                                        player
                                ),
                                LANGUAGE_SAVE_FAILED
                        )
        );

        openLanguageSelectionDialog(
                player
        );
    }

    private SupportedLanguage resolveDisplayLanguage(
            Player player
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        return plugin.coreApi()
                .playerManager()
                .find(
                        player.getUniqueId()
                )
                .map(
                        plugin.coreApi()
                                .localizationService()
                                ::languageOf
                )
                .orElse(
                        SupportedLanguage.ENGLISH
                );
    }

    private Throwable unwrap(
            Throwable throwable
    ) {
        Throwable current =
                throwable;

        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current =
                    current.getCause();
        }

        return current;
    }

    private String safeMessage(
            Throwable throwable
    ) {
        String message =
                throwable.getMessage();

        if (message == null
                || message.isBlank()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}
