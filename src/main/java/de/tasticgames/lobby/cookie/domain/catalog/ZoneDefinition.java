package de.tasticgames.lobby.cookie.domain.catalog;

import java.util.Objects;

/**
 * Immutable lobby zone definition.
 *
 * @param id          stable id
 * @param order       display order
 * @param minPrestige minimum prestige level required to enter
 * @param nameKey     translation key
 * @param displayName default English name
 */
public record ZoneDefinition(String id, int order, int minPrestige, String nameKey, String displayName) {

    public ZoneDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static ZoneDefinition of(String id, int order, int minPrestige, String displayName) {
        return new ZoneDefinition(id, order, minPrestige, "cookie.zone." + id, displayName);
    }
}
