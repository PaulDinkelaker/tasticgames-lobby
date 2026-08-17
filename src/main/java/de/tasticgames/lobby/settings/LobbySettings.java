package de.tasticgames.lobby.settings;

import de.tasticgames.settings.SettingKey;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lobby-specific typed settings registered into TasticCore's SettingRegistry.
 */
public final class LobbySettings {

    public static final Set<String> VISIBILITY_VALUES = Set.of("ALL", "FRIENDS", "PARTY", "FRIENDS_AND_PARTY", "NONE");

    public static final SettingKey<Boolean> ITEMS_ENABLED = SettingKey.booleanKey("lobby.items.enabled", true);
    public static final SettingKey<String> PLAYER_VISIBILITY = SettingKey.stringKey("lobby.player-visibility", "ALL",
            value -> value != null && VISIBILITY_VALUES.contains(value.trim().toUpperCase(Locale.ROOT)));
    public static final SettingKey<Boolean> LAUNCHPADS_ENABLED = SettingKey.booleanKey("lobby.launchpads.enabled", true);
    public static final SettingKey<Boolean> TELEPORT_PADS_ENABLED = SettingKey.booleanKey("lobby.teleport-pads.enabled", true);
    public static final SettingKey<Boolean> DOUBLE_JUMP_ENABLED = SettingKey.booleanKey("lobby.double-jump.enabled", true);
    public static final SettingKey<Boolean> COOKIE_HUD = SettingKey.booleanKey("lobby.cookie-clicker.hud", true);
    public static final SettingKey<Boolean> COOKIE_EFFECTS = SettingKey.booleanKey("lobby.cookie-clicker.effects", true);
    public static final SettingKey<Boolean> COOKIE_NOTIFICATIONS = SettingKey.booleanKey("lobby.cookie-clicker.notifications", true);
    public static final SettingKey<Boolean> HUD_ENABLED = SettingKey.booleanKey("lobby.hud.enabled", true);

    public static final List<SettingKey<?>> ALL = List.of(ITEMS_ENABLED, PLAYER_VISIBILITY, LAUNCHPADS_ENABLED,
            TELEPORT_PADS_ENABLED, DOUBLE_JUMP_ENABLED, COOKIE_HUD, COOKIE_EFFECTS, COOKIE_NOTIFICATIONS, HUD_ENABLED);

    private LobbySettings() {
    }
}
