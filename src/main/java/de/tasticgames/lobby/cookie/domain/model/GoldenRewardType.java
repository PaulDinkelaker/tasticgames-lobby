package de.tasticgames.lobby.cookie.domain.model;

/**
 * Special cookie reward kinds. The magnitudes and durations are per rarity
 * (see {@code SpecialCookieTuning}); which kinds a rarity can roll at all is decided by its
 * reward weights – {@link #CHAIN_BONUS} is off for SILVER and {@link #BLESSING} is DIAMOND/MASTER only.
 */
public enum GoldenRewardType {
    /** Instant cookies: {@code min(bank fraction, CPS window) + flat bonus}. */
    LUCKY,
    /** CPS and click power multiplied for the rarity's frenzy duration. */
    FRENZY,
    /** Click power multiplied for a short duration. */
    CLICK_FRENZY,
    /** Instant fraction of the bank. */
    CHAIN_BONUS,
    /** CPS and click power multiplied by two different factors at once (DIAMOND and MASTER). */
    BLESSING
}
