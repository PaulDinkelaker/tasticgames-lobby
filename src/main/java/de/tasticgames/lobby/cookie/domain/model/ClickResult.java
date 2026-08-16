package de.tasticgames.lobby.cookie.domain.model;

/**
 * Outcome of a single click.
 *
 * @param reward          cookies granted by this click (zero if rate limited)
 * @param comboStage      combo stage after the click
 * @param comboMultiplier combo multiplier applied
 * @param rateLimited     true if the click exceeded the server-side rate cap (counted, but no reward)
 * @param comboAdvanced   true if this click advanced the combo stage
 * @param cookiesAfter    bank after the click
 */
public record ClickResult(
        CookieAmount reward,
        int comboStage,
        double comboMultiplier,
        boolean rateLimited,
        boolean comboAdvanced,
        CookieAmount cookiesAfter
) {
}
