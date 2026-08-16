package de.tasticgames.lobby.cookie.domain.catalog;

import java.util.Objects;

/**
 * Effect of an upgrade.
 *
 * @param type        effect type
 * @param generatorId target generator for {@link UpgradeEffectType#GENERATOR_MULTIPLIER}, otherwise {@code null}
 * @param value       multiplier / percent / fraction depending on {@code type}
 */
public record UpgradeEffect(UpgradeEffectType type, String generatorId, double value) {

    public UpgradeEffect {
        Objects.requireNonNull(type, "type");
        if (type == UpgradeEffectType.GENERATOR_MULTIPLIER && (generatorId == null || generatorId.isBlank())) {
            throw new IllegalArgumentException("GENERATOR_MULTIPLIER requires a generatorId");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Effect value must be finite");
        }
    }

    public static UpgradeEffect clickMultiplier(double x) {
        return new UpgradeEffect(UpgradeEffectType.CLICK_POWER_MULTIPLIER, null, x);
    }

    public static UpgradeEffect clickAddCpsPercent(double percent) {
        return new UpgradeEffect(UpgradeEffectType.CLICK_POWER_ADD_CPS_PERCENT, null, percent);
    }

    public static UpgradeEffect globalCps(double x) {
        return new UpgradeEffect(UpgradeEffectType.GLOBAL_CPS_MULTIPLIER, null, x);
    }

    public static UpgradeEffect generatorMultiplier(String generatorId, double x) {
        return new UpgradeEffect(UpgradeEffectType.GENERATOR_MULTIPLIER, generatorId, x);
    }

    public static UpgradeEffect goldenFrequency(double x) {
        return new UpgradeEffect(UpgradeEffectType.GOLDEN_COOKIE_FREQUENCY, null, x);
    }

    public static UpgradeEffect goldenValue(double x) {
        return new UpgradeEffect(UpgradeEffectType.GOLDEN_COOKIE_VALUE, null, x);
    }

    public static UpgradeEffect comboDuration(double x) {
        return new UpgradeEffect(UpgradeEffectType.COMBO_DURATION, null, x);
    }

    public static UpgradeEffect offlineEfficiency(double addFraction) {
        return new UpgradeEffect(UpgradeEffectType.OFFLINE_EFFICIENCY, null, addFraction);
    }
}
