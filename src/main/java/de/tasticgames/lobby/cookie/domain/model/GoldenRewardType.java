package de.tasticgames.lobby.cookie.domain.model;

/** Golden cookie reward kinds. */
public enum GoldenRewardType {
    /** Instant cookies: min(15% of bank, 15 minutes of CPS) + 13. */
    LUCKY,
    /** CPS and click power x7 for the golden buff duration. */
    FRENZY,
    /** Click power x777 for a short duration. */
    CLICK_FRENZY,
    /** Instant 5% of the bank. */
    CHAIN_BONUS
}
