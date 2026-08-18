package de.tasticgames.lobby.dialog;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.visibility.VisibilityMode;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.settings.CoreSettings;
import de.tasticgames.settings.SettingKey;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Settings UI (Paper dialogs): categories → form with typed inputs → persisted via TasticCore.
 */
public final class SettingsDialogService {

    private record BoolSetting(SettingKey<Boolean> key, String labelKey) {
    }

    private record Category(String id, String titleKey, List<BoolSetting> booleans, boolean audioSliders, boolean language, boolean visibility,
                            boolean hudMode) {
        Category(String id, String titleKey, List<BoolSetting> booleans, boolean audioSliders, boolean language, boolean visibility) {
            this(id, titleKey, booleans, audioSliders, language, visibility, false);
        }
    }

    private final TasticCoreApi coreApi;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbySounds sounds;
    private final LanguageDialogService languageDialog;
    private final Logger logger;
    private final List<Category> categories;

    public SettingsDialogService(TasticCoreApi coreApi, LobbyMessages messages, DialogSupport dialogs, MainThread mainThread,
                                 LobbySounds sounds, LanguageDialogService languageDialog, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.sounds = Objects.requireNonNull(sounds);
        this.languageDialog = Objects.requireNonNull(languageDialog);
        this.logger = Objects.requireNonNull(logger);
        this.categories = List.of(
                new Category("general", "lobby.settings.category.general", List.of(new BoolSetting(CoreSettings.UI_ANIMATIONS, "settings.ui-animations")), false, true, false),
                new Category("audio", "lobby.settings.category.audio", List.of(new BoolSetting(CoreSettings.MUSIC_ENABLED, "settings.music"),
                        new BoolSetting(CoreSettings.SOUNDS_ENABLED, "settings.server-sounds")), true, false, false),
                new Category("accessibility", "lobby.settings.category.accessibility", List.of(new BoolSetting(CoreSettings.REDUCED_EFFECTS, "settings.reduced-effects")), false, false, false),
                new Category("social", "lobby.settings.category.social", List.of(new BoolSetting(CoreSettings.PRIVATE_MESSAGES, "settings.private-messages"),
                        new BoolSetting(CoreSettings.FRIEND_REQUESTS, "settings.friend-requests"), new BoolSetting(CoreSettings.PARTY_INVITES, "settings.party-invites")), false, false, false),
                new Category("notifications", "lobby.settings.category.notifications", List.of(new BoolSetting(CoreSettings.EVENT_NOTIFICATIONS, "settings.notifications")), false, false, false),
                new Category("cosmetics", "lobby.settings.category.cosmetics", List.of(new BoolSetting(CoreSettings.COSMETICS_VISIBLE, "settings.cosmetics-visible")), false, false, false),
                new Category("lobby", "lobby.settings.category.lobby", List.of(new BoolSetting(LobbySettings.ITEMS_ENABLED, "lobby.settings.items"),
                        new BoolSetting(LobbySettings.LAUNCHPADS_ENABLED, "lobby.settings.launchpads"),
                        new BoolSetting(LobbySettings.TELEPORT_PADS_ENABLED, "lobby.settings.teleport-pads"),
                        new BoolSetting(LobbySettings.DOUBLE_JUMP_ENABLED, "lobby.settings.double-jump"),
                        new BoolSetting(LobbySettings.JOIN_MESSAGES, "lobby.settings.join-messages")), false, false, true),
                new Category("hud", "lobby.settings.category.hud", List.of(new BoolSetting(LobbySettings.HUD_ENABLED, "lobby.settings.hud"),
                        new BoolSetting(LobbySettings.HUD_STATUS_ROW, "lobby.settings.hud-status"),
                        new BoolSetting(LobbySettings.HUD_HINTS, "lobby.settings.hud-hints"),
                        new BoolSetting(LobbySettings.COOKIE_HUD, "lobby.settings.cookie-hud")), false, false, false, true),
                new Category("cookie", "lobby.settings.category.cookie", List.of(
                        new BoolSetting(LobbySettings.COOKIE_EFFECTS, "lobby.settings.cookie-effects"),
                        new BoolSetting(LobbySettings.COOKIE_NOTIFICATIONS, "lobby.settings.cookie-notifications"),
                        new BoolSetting(LobbySettings.COOKIE_ACTIONBAR, "lobby.settings.cookie-actionbar"),
                        new BoolSetting(LobbySettings.COOKIE_CLICK_SOUNDS, "lobby.settings.cookie-click-sounds"),
                        new BoolSetting(LobbySettings.COOKIE_COMBO_POPUPS, "lobby.settings.cookie-combo-popups"),
                        new BoolSetting(LobbySettings.COOKIE_SPECIAL_ALERTS, "lobby.settings.cookie-special-alerts"),
                        new BoolSetting(LobbySettings.COOKIE_OFFLINE_PROMPT, "lobby.settings.cookie-offline-prompt"),
                        new BoolSetting(LobbySettings.COOKIE_CONFIRM_PRESTIGE, "lobby.settings.cookie-confirm-prestige")), false, false, false));
    }

    public void openMain(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        List<ActionButton> buttons = new ArrayList<>();
        for (Category category : categories) {
            buttons.add(dialogs.button(player, messages.get(lang, category.titleKey(), Map.of()), null, DialogSupport.BUTTON_WIDTH, p -> openCategory(p, category)));
        }
        dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.settings.title", Map.of()),
                List.of(messages.get(lang, "lobby.settings.description", Map.of())), buttons,
                dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
    }

    private void openCategory(Player player, Category category) {
        TasticPlayer tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic == null) {
            messages.send(player, "common.error");
            return;
        }
        SupportedLanguage lang = messages.languageOf(player);
        List<DialogInput> inputs = new ArrayList<>();
        for (BoolSetting setting : category.booleans()) {
            inputs.add(DialogInput.bool(inputKey(setting.key().id()), messages.get(lang, setting.labelKey(), Map.of()))
                    .initial(tastic.settings().get(setting.key())).build());
        }
        if (category.hudMode()) {
            List<SingleOptionDialogInput.OptionEntry> modes = new ArrayList<>();
            String current = tastic.settings().get(LobbySettings.HUD_MODE);
            for (String mode : List.of("FULL", "COMPACT", "MINIMAL")) {
                modes.add(SingleOptionDialogInput.OptionEntry.create(mode,
                        messages.get(lang, "lobby.settings.hud-mode." + mode.toLowerCase(java.util.Locale.ROOT), Map.of()), mode.equals(current)));
            }
            inputs.add(DialogInput.singleOption(inputKey(LobbySettings.HUD_MODE.id()),
                    messages.get(lang, "lobby.settings.hud-mode", Map.of()), modes).build());
        }
        if (category.audioSliders()) {
            inputs.add(DialogInput.numberRange(inputKey(CoreSettings.MUSIC_VOLUME.id()), messages.get(lang, "settings.music-volume", Map.of()), 0f, 100f)
                    .initial((float) tastic.settings().get(CoreSettings.MUSIC_VOLUME)).step(5f).build());
            inputs.add(DialogInput.numberRange(inputKey(CoreSettings.SOUND_VOLUME.id()), messages.get(lang, "settings.sound-volume", Map.of()), 0f, 100f)
                    .initial((float) tastic.settings().get(CoreSettings.SOUND_VOLUME)).step(5f).build());
        }
        if (category.language()) {
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            SupportedLanguage current = coreApi.localizationService().languageOf(tastic);
            for (SupportedLanguage language : SupportedLanguage.values()) {
                entries.add(SingleOptionDialogInput.OptionEntry.create(language.code(), Component.text(language.displayName()), language == current));
            }
            inputs.add(DialogInput.singleOption("language", messages.get(lang, "lobby.settings.language", Map.of()), entries).build());
        }
        if (category.visibility()) {
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            VisibilityMode current = VisibilityMode.find(tastic.settings().get(LobbySettings.PLAYER_VISIBILITY)).orElse(VisibilityMode.ALL);
            for (VisibilityMode mode : VisibilityMode.values()) {
                entries.add(SingleOptionDialogInput.OptionEntry.create(mode.name(),
                        messages.get(lang, "lobby.visibility." + mode.name().toLowerCase(java.util.Locale.ROOT), Map.of()), mode == current));
            }
            inputs.add(DialogInput.singleOption("visibility", messages.get(lang, "lobby.settings.visibility", Map.of()), entries).build());
        }
        ActionButton save = dialogs.formButton(player, messages.get(lang, "common.confirm", Map.of()), DialogSupport.BUTTON_WIDTH,
                (p, view) -> save(p, category, view));
        ActionButton back = dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openMain);
        dialogs.show(player, dialogs.form(messages.get(lang, category.titleKey(), Map.of()), List.of(), inputs, save, back));
    }

    private void save(Player player, Category category, io.papermc.paper.dialog.DialogResponseView view) {
        TasticPlayer tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic == null) {
            return;
        }
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (BoolSetting setting : category.booleans()) {
            Boolean value = view.getBoolean(inputKey(setting.key().id()));
            if (value != null && !value.equals(tastic.settings().get(setting.key()))) {
                futures.add(coreApi.playerSettingUpdateDispatcher().update(tastic, setting.key(), value));
            }
        }
        if (category.audioSliders()) {
            Float music = view.getFloat(inputKey(CoreSettings.MUSIC_VOLUME.id()));
            Float sound = view.getFloat(inputKey(CoreSettings.SOUND_VOLUME.id()));
            if (music != null && Math.round(music) != tastic.settings().get(CoreSettings.MUSIC_VOLUME)) {
                futures.add(coreApi.playerSettingUpdateDispatcher().update(tastic, CoreSettings.MUSIC_VOLUME, Math.round(music)));
            }
            if (sound != null && Math.round(sound) != tastic.settings().get(CoreSettings.SOUND_VOLUME)) {
                futures.add(coreApi.playerSettingUpdateDispatcher().update(tastic, CoreSettings.SOUND_VOLUME, Math.round(sound)));
            }
        }
        if (category.hudMode()) {
            String mode = view.getText(inputKey(LobbySettings.HUD_MODE.id()));
            if (mode != null && !mode.equalsIgnoreCase(tastic.settings().get(LobbySettings.HUD_MODE))) {
                futures.add(coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.HUD_MODE, mode.toUpperCase(java.util.Locale.ROOT)));
            }
        }
        if (category.visibility()) {
            String visibility = view.getText("visibility");
            if (visibility != null && !visibility.equalsIgnoreCase(tastic.settings().get(LobbySettings.PLAYER_VISIBILITY))) {
                futures.add(coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.PLAYER_VISIBILITY, visibility.toUpperCase(java.util.Locale.ROOT)));
            }
        }
        String language = category.language() ? view.getText("language") : null;
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                logger.warning("Settings save failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                messages.send(player, "settings.save-failed");
                sounds.error(player);
                return;
            }
            if (!futures.isEmpty()) {
                messages.send(player, "settings.saved");
                sounds.success(player);
            }
            if (language != null) {
                SupportedLanguage selected = SupportedLanguage.find(language).orElse(null);
                if (selected != null && selected != coreApi.localizationService().languageOf(tastic)) {
                    languageDialog.select(player, selected, () -> openMain(player), () -> {
                        messages.send(player, "settings.save-failed");
                        openMain(player);
                    });
                    return;
                }
            }
            openMain(player);
        }));
    }

    /** Paper dialog input keys allow only [a-zA-Z0-9_]; setting ids contain dots and dashes. */
    static String inputKey(String settingId) {
        return settingId.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
