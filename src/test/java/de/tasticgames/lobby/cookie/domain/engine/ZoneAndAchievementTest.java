package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.ZoneAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneAndAchievementTest {

    private final CookieEngine engine = TestSupport.engine();

    @Test
    void zoneGatingByPrestige() {
        CookieProfile p = TestSupport.fresh();
        assertTrue(engine.canEnter(p, "bakery_square").allowed());
        ZoneAccess denied = engine.canEnter(p, "sugar_fields");
        assertFalse(denied.allowed());
        assertEquals(ZoneAccess.Reason.PRESTIGE_TOO_LOW, denied.reason());
        assertEquals(1, denied.requiredPrestige());
        assertEquals(0, denied.currentPrestige());
        ZoneAccess unknown = engine.canEnter(p, "moon_base");
        assertFalse(unknown.allowed());
        assertEquals(ZoneAccess.Reason.UNKNOWN_ZONE, unknown.reason());
        assertEquals(-1, unknown.requiredPrestige());

        p.setPrestigeLevel(6);
        for (ZoneDefinition z : engine.catalog().zones()) {
            assertEquals(z.minPrestige() <= 6, engine.canEnter(p, z.id()).allowed(), z.id());
        }
    }

    @Test
    void discoveryIsIdempotentAndGated() {
        CookieProfile p = TestSupport.fresh();
        p.markClean();
        assertFalse(engine.discover(p, "sugar_fields"));
        assertFalse(p.isDirty());
        assertTrue(engine.discover(p, "bakery_square"));
        assertTrue(p.isDirty());
        assertFalse(engine.discover(p, "bakery_square"));
        assertEquals(1, p.discoveredZones().size());
        assertFalse(engine.discover(p, "moon_base"));
    }

    @Test
    void achievementsUnlockOnceAndAreReturnedOnce() {
        CookieProfile p = TestSupport.fresh();
        assertTrue(engine.evaluateAchievements(p).isEmpty());
        p.earn(CookieAmount.ONE);
        assertEquals(List.of("first_cookie"), engine.evaluateAchievements(p));
        assertTrue(engine.evaluateAchievements(p).isEmpty());
        assertTrue(p.achievements().contains("first_cookie"));

        p.addClicks(100);
        p.addGenerators("cursor", 1);
        List<String> batch = engine.evaluateAchievements(p);
        assertEquals(List.of("clicks_100", "first_generator"), batch);

        p.addClicks(9900);
        p.setGenerators(Map.of("cursor", 300, "baker", 250));
        p.earn(CookieAmount.of("1e9"));
        p.incrementGoldenCookiesClicked();
        p.setPrestigeLevel(5);
        List<String> more = engine.evaluateAchievements(p);
        assertTrue(more.containsAll(List.of("clicks_1000", "clicks_10000", "lifetime_1m", "lifetime_1b",
                "generators_100", "generators_500", "first_golden", "first_prestige", "prestige_5")), more.toString());
        assertFalse(more.contains("prestige_10"));
        assertFalse(more.contains("golden_100"));
        assertFalse(more.contains("all_zones_discovered"));

        p.setPrestigeLevel(10);
        for (ZoneDefinition z : engine.catalog().zones()) engine.discover(p, z.id());
        List<String> last = engine.evaluateAchievements(p);
        assertTrue(last.contains("prestige_10"));
        assertTrue(last.contains("all_zones_discovered"));
        assertEquals(engine.catalog().achievements().size() - 1, p.achievements().size()); // golden_100 missing
    }

    @Test
    void evaluatorWorksOnSnapshots() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of(5));
        AchievementEvaluator evaluator = engine.achievementEvaluator();
        assertEquals(List.of("first_cookie"), evaluator.satisfied(p.snapshot()));
        p.addAchievement("first_cookie");
        assertTrue(evaluator.newlySatisfied(p.snapshot()).isEmpty());
    }
}
