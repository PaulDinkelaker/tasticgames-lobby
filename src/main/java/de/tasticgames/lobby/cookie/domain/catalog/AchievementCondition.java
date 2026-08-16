package de.tasticgames.lobby.cookie.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Condition of an achievement, evaluated against a profile snapshot.
 *
 * @param type      what is measured
 * @param threshold required value (ignored for {@link Type#ALL_ZONES_DISCOVERED})
 */
public record AchievementCondition(Type type, BigDecimal threshold) {

    public enum Type {
        LIFETIME_COOKIES,
        TOTAL_CLICKS,
        TOTAL_GENERATORS,
        GOLDEN_COOKIES_CLICKED,
        PRESTIGE_LEVEL,
        ALL_ZONES_DISCOVERED
    }

    public AchievementCondition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(threshold, "threshold");
    }

    public static AchievementCondition of(Type type, long threshold) {
        return new AchievementCondition(type, BigDecimal.valueOf(threshold));
    }

    public static AchievementCondition of(Type type, String threshold) {
        return new AchievementCondition(type, new BigDecimal(threshold));
    }

    public static AchievementCondition allZonesDiscovered() {
        return new AchievementCondition(Type.ALL_ZONES_DISCOVERED, BigDecimal.ZERO);
    }
}
