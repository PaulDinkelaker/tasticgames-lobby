package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.model.ActiveBuff;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.Contribution;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static de.tasticgames.lobby.cookie.domain.TestSupport.assertBigEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    private final CookieEngine engine = TestSupport.engine();

    @Test
    void emptyProfileHasBaseClickAndZeroCps() {
        CookieStats s = engine.compute(TestSupport.fresh());
        assertBigEquals("1", s.clickValue());
        assertBigEquals("0", s.effectiveCps());
        assertTrue(s.contributions().isEmpty());
        assertEquals(1.0, s.prestigeMultiplier());
        assertEquals(0.5, s.offlineEfficiency());
        assertEquals(1.0, s.goldenChanceMultiplier());
        assertEquals(1.0, s.comboDurationMultiplier());
    }

    @Test
    void generatorsWithMilestones() {
        CookieProfile p = TestSupport.fresh();
        p.setGenerators(Map.of("cursor", 9, "baker", 1));
        assertBigEquals("0.75", engine.compute(p).effectiveCps());       // 9*0.05 + 0.3
        p.setGenerators(Map.of("cursor", 10, "baker", 1));
        assertBigEquals("1.3", engine.compute(p).effectiveCps());        // 10*0.05*2 + 0.3
        p.setGenerators(Map.of("cursor", 25));
        assertBigEquals("5", engine.compute(p).effectiveCps());          // 25*0.05*4
        p.setGenerators(Map.of("cursor", 200));
        assertBigEquals("320", engine.compute(p).effectiveCps());        // 200*0.05*32
    }

    @Test
    void generatorAndGlobalUpgradesAndPrestigeAndTree() {
        CookieProfile p = TestSupport.fresh();
        p.setGenerators(Map.of("cursor", 10, "baker", 1));
        p.addUpgrade("cursor_tier_1");                                   // cursors x2
        assertBigEquals("2.3", engine.compute(p).effectiveCps());        // 2.0 + 0.3
        p.addUpgrade("kitchen_synergy");                                 // global x1.1
        assertBigEquals("2.53", engine.compute(p).effectiveCps());
        p.setPrestigeLevel(1);                                           // x1.25
        assertBigEquals("3.1625", engine.compute(p).effectiveCps());
        p.setPrestigeUpgradeLevel("efficient_ovens", 2);                 // +10%
        CookieStats s = engine.compute(p);
        assertBigEquals("3.47875", s.effectiveCps());
        assertEquals(1.25, s.prestigeMultiplier());
        assertEquals(1.1, s.upgradeCpsMultiplier(), 1e-12);
        assertEquals(1.1, s.treeCpsMultiplier(), 1e-12);
        assertBigEquals("2.3", s.baseCps());
        // contributions add up to the effective cps
        BigDecimal sum = s.contributions().stream().map(Contribution::cpsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertBigEquals("3.47875", sum);
        assertEquals(2, s.contributions().size());
        assertEquals("cursor", s.contributions().getFirst().generatorId());
        assertEquals(10, s.contributions().getFirst().count());
    }

    @Test
    void clickValueUsesUpgradesTreePrestigeAndCpsShare() {
        CookieProfile p = TestSupport.fresh();
        p.addUpgrade("reinforced_index_finger");
        p.addUpgrade("carpal_tunnel");
        assertBigEquals("4", engine.compute(p).clickValue());
        p.setPrestigeUpgradeLevel("iron_fingers", 3);                    // +30%
        assertBigEquals("5.2", engine.compute(p).clickValue());
        p.setPrestigeLevel(2);                                           // x1.6
        assertBigEquals("8.32", engine.compute(p).clickValue());
        p.setGenerators(Map.of("oven", 60));                             // 60*2.5*8 = 1200 base, x1.6 = 1920 cps
        p.addUpgrade("thousand_fingers");                                // +1% of cps per click = 19.2
        CookieStats s = engine.compute(p);
        assertBigEquals("1920", s.effectiveCps());
        assertBigEquals("27.52", s.clickValue());
        assertEquals(1.0, s.clickCpsPercent());
    }

    @Test
    void buffsAffectCpsAndClicksByType() {
        CookieProfile p = TestSupport.fresh();
        p.setGenerators(Map.of("baker", 10));                            // 10*0.3*2 = 6 cps
        p.addBuff(ActiveBuff.of(BuffType.FRENZY, 7, T0, Duration.ofSeconds(30), "test"));
        CookieStats s = engine.compute(p);
        assertBigEquals("42", s.effectiveCps());
        assertBigEquals("6", s.unbuffedCps());
        assertBigEquals("7", s.clickValue());
        assertEquals(7.0, s.buffCpsMultiplier());
        assertEquals(7.0, s.buffClickMultiplier());

        p.clearBuffs();
        p.addBuff(ActiveBuff.of(BuffType.CLICK_FRENZY, 777, T0, Duration.ofSeconds(13), "test"));
        p.addBuff(ActiveBuff.of(BuffType.CPS_MULTIPLIER, 2, T0, Duration.ofSeconds(13), "test"));
        s = engine.compute(p);
        assertBigEquals("12", s.effectiveCps());
        assertBigEquals("777", s.clickValue());

        // expiry
        assertEquals(0, engine.expireBuffs(p, T0.plusSeconds(12)));
        assertEquals(2, engine.expireBuffs(p, T0.plusSeconds(13)));
        assertTrue(p.activeBuffs().isEmpty());
        assertBigEquals("6", engine.compute(p).effectiveCps());
    }

    @Test
    void offlineGoldenAndComboMultipliers() {
        CookieProfile p = TestSupport.fresh();
        p.addUpgrade("night_shift");                                     // +25 pp
        p.setPrestigeUpgradeLevel("night_bakers", 5);                    // +50 pp → capped at 100%
        p.addUpgrade("golden_luck");                                     // x1.5
        p.setPrestigeUpgradeLevel("lucky_charms", 2);                    // +20%
        p.addUpgrade("golden_glow");                                     // value x2
        p.setPrestigeUpgradeLevel("lasting_glow", 1);                    // duration +10%
        p.addUpgrade("sugar_rush");                                      // combo x1.5
        p.setPrestigeUpgradeLevel("sugar_high", 2);                      // +20%
        CookieStats s = engine.compute(p);
        assertEquals(1.0, s.offlineEfficiency());
        assertEquals(1.8, s.goldenChanceMultiplier(), 1e-12);
        assertEquals(2.0, s.goldenValueMultiplier());
        assertEquals(1.1, s.goldenDurationMultiplier(), 1e-12);
        assertEquals(1.8, s.comboDurationMultiplier(), 1e-12);
    }

    @Test
    void unknownUpgradeIdsAreIgnored() {
        CookieProfile p = TestSupport.fresh();
        p.addUpgrade("removed_upgrade_from_old_version");
        assertBigEquals("1", engine.compute(p).clickValue());
    }
}
