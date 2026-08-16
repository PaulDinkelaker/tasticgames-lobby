package de.tasticgames.lobby.cookie.domain.catalog;

import java.util.Objects;

/**
 * Immutable achievement definition.
 *
 * @param id        stable id
 * @param nameKey   translation key
 * @param condition unlock condition
 */
public record AchievementDefinition(String id, String nameKey, AchievementCondition condition) {

    public AchievementDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(condition, "condition");
    }

    public static AchievementDefinition of(String id, AchievementCondition condition) {
        return new AchievementDefinition(id, "cookie.achievement." + id, condition);
    }
}
