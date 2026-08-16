package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.model.ClickResult;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static de.tasticgames.lobby.cookie.domain.TestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickTest {

    private final CookieEngine engine = TestSupport.engine();

    @Test
    void singleClickEarnsClickValue() {
        CookieProfile p = TestSupport.fresh();
        ClickResult r = engine.click(p, T0);
        assertAmount("1", r.reward());
        assertEquals(0, r.comboStage());
        assertEquals(1.0, r.comboMultiplier());
        assertFalse(r.rateLimited());
        assertAmount("1", p.cookies());
        assertAmount("1", p.lifetimeCookies());
        assertEquals(1, p.totalClicks());
        assertTrue(p.isDirty());
    }

    @Test
    void comboGrowsEveryFiveRapidClicksUpToMax() {
        CookieProfile p = TestSupport.fresh();
        Instant t = T0;
        int[] expectedStageAfterClick = new int[25];
        for (int i = 1; i <= 25; i++) expectedStageAfterClick[i - 1] = Math.min(4, i / 5);
        for (int i = 1; i <= 25; i++) {
            ClickResult r = engine.click(p, t);
            assertEquals(expectedStageAfterClick[i - 1], r.comboStage(), "click " + i);
            assertFalse(r.rateLimited(), "click " + i);
            assertEquals(engine.balancing().comboMultiplier(r.comboStage()), r.comboMultiplier());
            assertEquals(i % 5 == 0 && i <= 20, r.comboAdvanced(), "click " + i);
            t = t.plusMillis(100);
        }
        assertEquals(4, p.highestCombo());
        // reward at max combo is x2
        ClickResult r = engine.click(p, t);
        assertAmount("2", r.reward());
        assertEquals(2.0, r.comboMultiplier());
    }

    @Test
    void comboDecaysAfterIdleButSurvivesShortGaps() {
        CookieProfile p = TestSupport.fresh();
        Instant t = T0;
        for (int i = 0; i < 10; i++) {
            engine.click(p, t);
            t = t.plusMillis(100);
        }
        assertEquals(2, p.comboStage());
        // gap of 1.5 s: longer than the window (800 ms) but shorter than decay (3000 ms) → stage kept
        t = t.plusMillis(1500);
        ClickResult kept = engine.click(p, t);
        assertEquals(2, kept.comboStage());
        // idle for more than the decay time → reset
        t = t.plusMillis(3001);
        ClickResult reset = engine.click(p, t);
        assertEquals(0, reset.comboStage());
        assertEquals(1.0, reset.comboMultiplier());
        assertEquals(2, p.highestCombo());
    }

    @Test
    void comboDurationUpgradeExtendsDecay() {
        CookieProfile p = TestSupport.fresh();
        p.addUpgrade("sugar_rush"); // decay 3000 * 1.5 = 4500 ms
        Instant t = T0;
        for (int i = 0; i < 5; i++) {
            engine.click(p, t);
            t = t.plusMillis(100);
        }
        assertEquals(1, p.comboStage());
        t = t.plusMillis(4000);
        assertEquals(1, engine.click(p, t).comboStage());
        t = t.plusMillis(4600);
        assertEquals(0, engine.click(p, t).comboStage());
    }

    @Test
    void decayComboHelperResetsIdleCombo() {
        CookieProfile p = TestSupport.fresh();
        Instant t = T0;
        for (int i = 0; i < 5; i++) {
            engine.click(p, t);
            t = t.plusMillis(50);
        }
        assertEquals(1, p.comboStage());
        assertFalse(engine.decayCombo(p, t.plusMillis(1000)));
        assertTrue(engine.decayCombo(p, t.plusMillis(5000)));
        assertEquals(0, p.comboStage());
    }

    @Test
    void clickRateCapCountsButDoesNotReward() {
        CookieProfile p = TestSupport.fresh();
        Instant t = T0;
        int limited = 0;
        for (int i = 1; i <= 20; i++) {
            ClickResult r = engine.click(p, t);
            if (i <= 15) {
                assertFalse(r.rateLimited(), "click " + i);
                assertTrue(r.reward().isPositive());
            } else {
                assertTrue(r.rateLimited(), "click " + i);
                assertTrue(r.reward().isZero());
                limited++;
            }
            t = t.plusMillis(10);
        }
        assertEquals(5, limited);
        assertEquals(20, p.totalClicks());
        // exactly 15 rewarded clicks (with combo multipliers): 4x1 + 5x1.1 + 5x1.25 + 1x1.5
        assertAmount("17.25", p.cookies());
        // one second later the window has passed and clicks are rewarded again
        ClickResult later = engine.click(p, t.plusMillis(1000));
        assertFalse(later.rateLimited());
    }

    @Test
    void customBalancingChangesCapAndStages() {
        CookieBalancing b = CookieBalancing.builder().maxClicksPerSecond(2).clicksPerComboStage(2).build();
        CookieEngine e = TestSupport.engine(b);
        CookieProfile p = TestSupport.fresh();
        assertFalse(e.click(p, T0).rateLimited());
        ClickResult second = e.click(p, T0.plusMillis(100));
        assertFalse(second.rateLimited());
        assertEquals(1, second.comboStage());
        assertTrue(e.click(p, T0.plusMillis(200)).rateLimited());
    }

    @Test
    void clickHistoryIsBounded() {
        CookieProfile p = TestSupport.fresh();
        for (int i = 0; i < 5000; i++) engine.click(p, T0.plusMillis(i * 200L));
        assertTrue(p.clickHistory().size() <= p.clickHistory().capacity());
        assertEquals(5000, p.totalClicks());
        assertTrue(p.cookies().compareTo(CookieAmount.of(5000)) >= 0);
    }
}
