package de.tasticgames.lobby.cookie.domain.model;

/**
 * Zone access decision.
 *
 * @param allowed          whether the player may enter
 * @param zoneId           zone id
 * @param requiredPrestige minimum prestige level (or -1 if the zone is unknown)
 * @param currentPrestige  the player's prestige level
 * @param reason           reason
 */
public record ZoneAccess(boolean allowed, String zoneId, int requiredPrestige, int currentPrestige, Reason reason) {

    public enum Reason {
        OK,
        UNKNOWN_ZONE,
        PRESTIGE_TOO_LOW
    }
}
