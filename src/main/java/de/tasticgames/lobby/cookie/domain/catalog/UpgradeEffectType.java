package de.tasticgames.lobby.cookie.domain.catalog;

/** Effect types an upgrade can have. */
public enum UpgradeEffectType {
    /** Multiplies click power by {@code value}. */
    CLICK_POWER_MULTIPLIER,
    /** Adds {@code value} percent of the effective CPS to every click. */
    CLICK_POWER_ADD_CPS_PERCENT,
    /** Multiplies the total CPS by {@code value}. */
    GLOBAL_CPS_MULTIPLIER,
    /** Multiplies the output of one generator ({@code generatorId}) by {@code value}. */
    GENERATOR_MULTIPLIER,
    /** Multiplies the golden cookie spawn chance by {@code value}. */
    GOLDEN_COOKIE_FREQUENCY,
    /** Multiplies instant golden cookie rewards by {@code value}. */
    GOLDEN_COOKIE_VALUE,
    /** Multiplies the combo decay duration by {@code value}. */
    COMBO_DURATION,
    /** Adds {@code value} (fraction, e.g. 0.25 = +25 percentage points) to the offline efficiency. */
    OFFLINE_EFFICIENCY
}
