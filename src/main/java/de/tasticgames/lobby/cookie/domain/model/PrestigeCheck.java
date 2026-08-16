package de.tasticgames.lobby.cookie.domain.model;

/**
 * Prestige eligibility check.
 *
 * @param eligible        whether the profile can prestige right now
 * @param currentLevel    current prestige level
 * @param nextLevel       next level, or -1 if the maximum level is reached
 * @param requiredLifetime lifetime cookies required for {@code nextLevel} (zero if at max)
 * @param lifetime        current lifetime cookies
 * @param missing         cookies still missing (zero if eligible or at max)
 * @param atMaxLevel      true if no further prestige exists
 */
public record PrestigeCheck(
        boolean eligible,
        int currentLevel,
        int nextLevel,
        CookieAmount requiredLifetime,
        CookieAmount lifetime,
        CookieAmount missing,
        boolean atMaxLevel
) {
}
