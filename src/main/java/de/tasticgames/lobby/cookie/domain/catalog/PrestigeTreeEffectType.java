package de.tasticgames.lobby.cookie.domain.catalog;

/** Effect types of prestige tree nodes. Values are "per level". */
public enum PrestigeTreeEffectType {
    /** +{@code valuePerLevel}% click power per level. */
    CLICK_POWER_PERCENT,
    /** +{@code valuePerLevel}% global CPS per level. */
    GLOBAL_CPS_PERCENT,
    /** +{@code valuePerLevel} percentage points offline efficiency per level (total capped at 100%). */
    OFFLINE_EFFICIENCY_PERCENT,
    /** +{@code valuePerLevel}% golden cookie spawn chance per level. */
    GOLDEN_CHANCE_PERCENT,
    /** +{@code valuePerLevel}% golden cookie buff duration per level. */
    GOLDEN_DURATION_PERCENT,
    /** +{@code valuePerLevel}% combo duration per level. */
    COMBO_DURATION_PERCENT,
    /** +{@code valuePerLevel} starting cookies per level on a new run. */
    STARTING_COOKIES,
    /** +{@code valuePerLevel} units of {@code targetGeneratorId} per level on a new run. */
    STARTING_GENERATORS
}
