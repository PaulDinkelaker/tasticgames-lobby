package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.catalog.AchievementCondition;
import de.tasticgames.lobby.cookie.domain.catalog.AchievementDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.model.CookieProfileSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates achievement conditions against a profile snapshot. Pure and thread-safe. */
public final class AchievementEvaluator {

    private final CookieCatalog catalog;

    public AchievementEvaluator(CookieCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Ids of all achievements whose condition is satisfied by the snapshot (owned or not). */
    public List<String> satisfied(CookieProfileSnapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (AchievementDefinition a : catalog.achievements()) {
            if (isSatisfied(a.condition(), snapshot)) result.add(a.id());
        }
        return result;
    }

    /** Ids of satisfied achievements the snapshot does not own yet. */
    public List<String> newlySatisfied(CookieProfileSnapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (AchievementDefinition a : catalog.achievements()) {
            if (!snapshot.achievements().contains(a.id()) && isSatisfied(a.condition(), snapshot)) {
                result.add(a.id());
            }
        }
        return result;
    }

    public boolean isSatisfied(AchievementCondition condition, CookieProfileSnapshot s) {
        BigDecimal t = condition.threshold();
        return switch (condition.type()) {
            case LIFETIME_COOKIES -> s.lifetimeCookies().toBigDecimal().compareTo(t) >= 0;
            case TOTAL_CLICKS -> BigDecimal.valueOf(s.totalClicks()).compareTo(t) >= 0;
            case TOTAL_GENERATORS -> BigDecimal.valueOf(s.totalGenerators()).compareTo(t) >= 0;
            case GOLDEN_COOKIES_CLICKED -> BigDecimal.valueOf(s.goldenCookiesClicked()).compareTo(t) >= 0;
            case PRESTIGE_LEVEL -> BigDecimal.valueOf(s.prestigeLevel()).compareTo(t) >= 0;
            case ALL_ZONES_DISCOVERED -> {
                for (ZoneDefinition z : catalog.zones()) {
                    if (!s.discoveredZones().contains(z.id())) yield false;
                }
                yield !catalog.zones().isEmpty();
            }
        };
    }
}
