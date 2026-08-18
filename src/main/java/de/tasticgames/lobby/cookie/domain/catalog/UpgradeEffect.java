package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;

import java.util.Objects;

/**
 * Effect of an upgrade.
 *
 * @param type        effect type
 * @param generatorId target generator for {@link UpgradeEffectType#GENERATOR_MULTIPLIER}, otherwise {@code null}
 * @param rarity      target rarity for {@link UpgradeEffectType#SPECIAL_RARITY_WEIGHT}, otherwise {@code null}
 * @param value       multiplier / percent / fraction depending on {@code type}
 */
public record UpgradeEffect(UpgradeEffectType type, String generatorId, SpecialCookieRarity rarity, double value) {

    public UpgradeEffect {
        Objects.requireNonNull(type, "type");
        if (type == UpgradeEffectType.GENERATOR_MULTIPLIER && (generatorId == null || generatorId.isBlank())) {
            throw new IllegalArgumentException("GENERATOR_MULTIPLIER requires a generatorId");
        }
        if (type == UpgradeEffectType.SPECIAL_RARITY_WEIGHT && rarity == null) {
            throw new IllegalArgumentException("SPECIAL_RARITY_WEIGHT requires a rarity");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Effect value must be finite");
        }
    }

    public static UpgradeEffect clickMultiplier(double x) {
        return new UpgradeEffect(UpgradeEffectType.CLICK_POWER_MULTIPLIER, null, null, x);
    }

    public static UpgradeEffect clickAddCpsPercent(double percent) {
        return new UpgradeEffect(UpgradeEffectType.CLICK_POWER_ADD_CPS_PERCENT, null, null, percent);
    }

    public static UpgradeEffect globalCps(double x) {
        return new UpgradeEffect(UpgradeEffectType.GLOBAL_CPS_MULTIPLIER, null, null, x);
    }

    public static UpgradeEffect generatorMultiplier(String generatorId, double x) {
        return new UpgradeEffect(UpgradeEffectType.GENERATOR_MULTIPLIER, generatorId, null, x);
    }

    public static UpgradeEffect goldenFrequency(double x) {
        return new UpgradeEffect(UpgradeEffectType.GOLDEN_COOKIE_FREQUENCY, null, null, x);
    }

    public static UpgradeEffect goldenValue(double x) {
        return new UpgradeEffect(UpgradeEffectType.GOLDEN_COOKIE_VALUE, null, null, x);
    }

    public static UpgradeEffect rarityWeight(SpecialCookieRarity rarity, double x) {
        return new UpgradeEffect(UpgradeEffectType.SPECIAL_RARITY_WEIGHT, null, rarity, x);
    }

    public static UpgradeEffect comboDuration(double x) {
        return new UpgradeEffect(UpgradeEffectType.COMBO_DURATION, null, null, x);
    }

    public static UpgradeEffect offlineEfficiency(double addFraction) {
        return new UpgradeEffect(UpgradeEffectType.OFFLINE_EFFICIENCY, null, null, addFraction);
    }
}
