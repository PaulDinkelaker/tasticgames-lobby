package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.model.ActiveBuff;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.OfflineResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static de.tasticgames.lobby.cookie.domain.TestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAndOfflineTest {

    private final CookieEngine engine = TestSupport.engine();

    private CookieProfile tenCursors() {
        CookieProfile p = TestSupport.fresh();
        p.setGenerators(Map.of("cursor", 10)); // 10 * 0.05 * 2 = 1 cps
        p.markClean();
        return p;
    }

    @Test
    void produceIsTimeBased() {
        CookieProfile p = tenCursors();
        assertAmount("10", engine.produce(p, Duration.ofSeconds(10)));
        assertAmount("0.5", engine.produce(p, Duration.ofMillis(500)));
        assertAmount("10.5", p.cookies());
        assertAmount("10.5", p.lifetimeCookies());
        assertTrue(p.isDirty());
        assertAmount("0", engine.produce(p, Duration.ZERO));
        assertAmount("0", engine.produce(p, Duration.ofSeconds(-5)));
        // lag: one long tick equals many short ticks
        CookieProfile a = tenCursors();
        CookieProfile b = tenCursors();
        engine.produce(a, Duration.ofSeconds(60));
        for (int i = 0; i < 1200; i++) engine.produce(b, Duration.ofMillis(50));
        assertEquals(a.cookies(), b.cookies());
        assertAmount("2", engine.produce(tenCursors(), T0, T0.plusSeconds(2)));
    }

    @Test
    void produceIncludesBuffs() {
        CookieProfile p = tenCursors();
        p.addBuff(ActiveBuff.of(BuffType.FRENZY, 7, T0, Duration.ofSeconds(30), "golden"));
        assertAmount("70", engine.produce(p, Duration.ofSeconds(10)));
    }

    @Test
    void offlineProductionAppliesEfficiencyAndCap() {
        CookieProfile p = tenCursors();
        Instant now = T0.plusSeconds(3600);
        OfflineResult r = engine.offlineProduction(p, T0, now);
        assertEquals(3600, r.seconds());
        assertEquals(3600, r.cappedSeconds());
        assertEquals(0.5, r.efficiency());
        assertAmount("1800", r.cookies());
        assertAmount("1800", p.cookies());
        assertEquals(now, p.offlineClaimedUntil());
        assertEquals(now, p.lastActiveAt());
        assertTrue(p.isDirty());

        // 10 hours away → capped at 8 hours
        CookieProfile q = tenCursors();
        OfflineResult capped = engine.offlineProduction(q, T0, T0.plusSeconds(36_000));
        assertEquals(36_000, capped.seconds());
        assertEquals(28_800, capped.cappedSeconds());
        assertAmount("14400", capped.cookies());
    }

    @Test
    void offlineUsesUpgradeAndTreeEfficiencyButNoBuffs() {
        CookieProfile p = tenCursors();
        p.addUpgrade("night_shift");                          // 0.5 + 0.25
        p.setPrestigeUpgradeLevel("night_bakers", 1);         // + 0.10 → 0.85
        p.addBuff(ActiveBuff.of(BuffType.FRENZY, 7, T0, Duration.ofSeconds(30), "golden"));
        OfflineResult r = engine.previewOffline(p, T0, T0.plusSeconds(100));
        assertEquals(0.85, r.efficiency(), 1e-12);
        assertAmount("85", r.cookies());
        assertAmount("0", p.cookies()); // preview does not mutate
    }

    @Test
    void offlineDisabledReturnsZero() {
        CookieEngine disabled = TestSupport.engine(CookieBalancing.builder().offlineEnabled(false).build());
        CookieProfile p = tenCursors();
        OfflineResult r = disabled.offlineProduction(p, T0, T0.plusSeconds(3600));
        assertTrue(r.isEmpty());
        assertEquals(0, r.cappedSeconds());
        assertAmount("0", p.cookies());
        assertFalse(p.isDirty());
    }

    @Test
    void offlineAlreadyClaimedWindowYieldsZero() {
        CookieProfile p = tenCursors();
        Instant now = T0.plusSeconds(600);
        engine.offlineProduction(p, T0, now);
        assertAmount("300", p.cookies());
        // duplicate claim with the same instant → nothing
        OfflineResult dup = engine.offlineProduction(p, T0, now);
        assertTrue(dup.isEmpty());
        assertAmount("300", p.cookies());
        // now before claimedUntil → nothing
        assertTrue(engine.offlineProduction(p, T0, now.minusSeconds(10)).isEmpty());
        // later claim only counts the time after the claimed window even if lastActive is older
        OfflineResult later = engine.offlineProduction(p, T0, now.plusSeconds(100));
        assertEquals(100, later.seconds());
        assertAmount("50", later.cookies());
        assertAmount("350", p.cookies());
    }

    @Test
    void offlineUsesProfileLastActiveOverload() {
        CookieProfile p = tenCursors();
        OfflineResult r = engine.offlineProduction(p, T0.plusSeconds(200));
        assertEquals(200, r.seconds());
        assertAmount("100", r.cookies());
        CookieProfile noTimestamp = CookieProfile.fresh(TestSupport.PLAYER);
        assertTrue(engine.offlineProduction(noTimestamp, T0).isEmpty());
        assertTrue(engine.offlineProduction(p, T0.plusSeconds(200), T0.plusSeconds(100)).isEmpty());
    }

    @Test
    void offlineWithoutGeneratorsIsZeroButStillMarksClaim() {
        CookieProfile p = TestSupport.fresh();
        OfflineResult r = engine.offlineProduction(p, T0, T0.plusSeconds(100));
        assertEquals(100, r.cappedSeconds());
        assertTrue(r.isEmpty());
        assertEquals(T0.plusSeconds(100), p.offlineClaimedUntil());
        assertEquals(CookieAmount.ZERO, p.cookies());
    }
}
