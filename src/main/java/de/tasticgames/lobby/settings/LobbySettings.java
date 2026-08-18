package de.tasticgames.lobby.settings;

import de.tasticgames.settings.SettingKey;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lobby-specific typed settings registered into TasticCore's SettingRegistry. Everything a player can turn on
 * or off in {@code /settings} lives here; the services read the value per player, so a change takes effect
 * immediately without a reconnect.
 */
public final class LobbySettings {

    public static final Set<String> VISIBILITY_VALUES = Set.of("ALL", "FRIENDS", "PARTY", "FRIENDS_AND_PARTY", "NONE");
    /** How much the HUD shows: everything, only the values row, or nothing but the status line. */
    public static final Set<String> HUD_MODES = Set.of("FULL", "COMPACT", "MINIMAL");

    // ---- lobby world
    public static final SettingKey<Boolean> ITEMS_ENABLED = SettingKey.booleanKey("lobby.items.enabled", true);
    public static final SettingKey<String> PLAYER_VISIBILITY = SettingKey.stringKey("lobby.player-visibility", "ALL",
            value -> value != null && VISIBILITY_VALUES.contains(value.trim().toUpperCase(Locale.ROOT)));
    public static final SettingKey<Boolean> LAUNCHPADS_ENABLED = SettingKey.booleanKey("lobby.launchpads.enabled", true);
    public static final SettingKey<Boolean> TELEPORT_PADS_ENABLED = SettingKey.booleanKey("lobby.teleport-pads.enabled", true);
    public static final SettingKey<Boolean> DOUBLE_JUMP_ENABLED = SettingKey.booleanKey("lobby.double-jump.enabled", true);

    // ---- HUD
    public static final SettingKey<Boolean> HUD_ENABLED = SettingKey.booleanKey("lobby.hud.enabled", true);
    public static final SettingKey<String> HUD_MODE = SettingKey.stringKey("lobby.hud.mode", "FULL",
            value -> value != null && HUD_MODES.contains(value.trim().toUpperCase(Locale.ROOT)));
    public static final SettingKey<Boolean> HUD_STATUS_ROW = SettingKey.booleanKey("lobby.hud.status-row", true);
    public static final SettingKey<Boolean> HUD_HINTS = SettingKey.booleanKey("lobby.hud.hints", true);

    // ---- cookie clicker
    public static final SettingKey<Boolean> COOKIE_HUD = SettingKey.booleanKey("lobby.cookie-clicker.hud", true);
    public static final SettingKey<Boolean> COOKIE_EFFECTS = SettingKey.booleanKey("lobby.cookie-clicker.effects", true);
    public static final SettingKey<Boolean> COOKIE_NOTIFICATIONS = SettingKey.booleanKey("lobby.cookie-clicker.notifications", true);
    public static final SettingKey<Boolean> COOKIE_CLICK_SOUNDS = SettingKey.booleanKey("lobby.cookie-clicker.click-sounds", true);
    public static final SettingKey<Boolean> COOKIE_ACTIONBAR = SettingKey.booleanKey("lobby.cookie-clicker.actionbar", true);
    public static final SettingKey<Boolean> COOKIE_SPECIAL_ALERTS = SettingKey.booleanKey("lobby.cookie-clicker.special-alerts", true);
    public static final SettingKey<Boolean> COOKIE_COMBO_POPUPS = SettingKey.booleanKey("lobby.cookie-clicker.combo-popups", true);
    public static final SettingKey<Boolean> COOKIE_OFFLINE_PROMPT = SettingKey.booleanKey("lobby.cookie-clicker.offline-prompt", true);
    public static final SettingKey<Boolean> COOKIE_CONFIRM_PRESTIGE = SettingKey.booleanKey("lobby.cookie-clicker.confirm-prestige", true);

    // ---- social & notifications
    public static final SettingKey<Boolean> JOIN_MESSAGES = SettingKey.booleanKey("lobby.join-messages", true);

    public static final List<SettingKey<?>> ALL = List.of(
            ITEMS_ENABLED, PLAYER_VISIBILITY, LAUNCHPADS_ENABLED, TELEPORT_PADS_ENABLED, DOUBLE_JUMP_ENABLED,
            HUD_ENABLED, HUD_MODE, HUD_STATUS_ROW, HUD_HINTS,
            COOKIE_HUD, COOKIE_EFFECTS, COOKIE_NOTIFICATIONS, COOKIE_CLICK_SOUNDS, COOKIE_ACTIONBAR,
            COOKIE_SPECIAL_ALERTS, COOKIE_COMBO_POPUPS, COOKIE_OFFLINE_PROMPT, COOKIE_CONFIRM_PRESTIGE,
            JOIN_MESSAGES);

    private LobbySettings() {
    }
}
