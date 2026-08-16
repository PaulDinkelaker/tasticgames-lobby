package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.GoldenCookieReward;
import de.tasticgames.lobby.cookie.domain.model.GoldenCookieRoll;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static de.tasticgames.lobby.cookie.domain.TestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenCookieTest {

    private final CookieEngine engine = TestSupport.engine();

    /** RNG stub returning fixed values. */
    private static RandomGenerator fixed(double d, int i) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public double nextDouble() { return d; }
            @Override public int nextInt(int bound) { return Math.min(i, bound - 1); }
        };
    }

    @Test
    void spawnProbabilityFollowsBalancingAndMultipliers() {
        CookieProfile p = TestSupport.fresh();
        assertEquals(1.0 / 300.0, engine.goldenSpawnProbabilityPerSecond(p), 1e-12);
        p.addUpgrade("golden_luck");
        p.setPrestigeUpgradeLevel("lucky_charms", 5); // 1.5 * 1.5
        assertEquals(2.25 / 300.0, engine.goldenSpawnProbabilityPerSecond(p), 1e-12);

        GoldenCookieRoll spawn = engine.roll(p, fixed(0.001, 0), T0);
        assertTrue(spawn.spawned());
        assertEquals(T0.plusSeconds(15), spawn.expiresAt());
        GoldenCookieRoll miss = engine.roll(p, fixed(0.5, 0), T0);
        assertFalse(miss.spawned());
        assertNull(miss.expiresAt());
        // multi-second roll uses 1-(1-p)^t
        GoldenCookieRoll multi = engine.roll(p, fixed(0.5, 0), T0, 300);
        assertEquals(1.0 - Math.pow(1.0 - 2.25 / 300.0, 300), multi.probability(), 1e-12);
    }

    @Test
    void luckyRewardIsMinOfBankShareAndCpsWindowPlusFlat() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("10000"));
        p.setGenerators(Map.of("cursor", 10)); // 1 cps → 15 min = 900 < 15% of 10000 = 1500
        GoldenCookieReward r = engine.rewardOfType(p, GoldenRewardType.LUCKY, T0);
        assertAmount("913", r.cookies());
        assertTrue(r.isInstant());
        // with a big bank the cps window dominates; with a small bank the bank share dominates
        p.setGenerators(Map.of("oven", 200)); // 200*2.5*32 = 16000 cps → 15 min = 14.4M
        assertAmount("1513", engine.rewardOfType(p, GoldenRewardType.LUCKY, T0).cookies());
        // golden_glow doubles instant rewards
        p.addUpgrade("golden_glow");
        assertAmount("3026", engine.rewardOfType(p, GoldenRewardType.LUCKY, T0).cookies());
    }

    @Test
    void chainBonusIsBankFraction() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("2000"));
        assertAmount("100", engine.rewardOfType(p, GoldenRewardType.CHAIN_BONUS, T0).cookies());
    }

    @Test
    void frenzyAndClickFrenzyProduceBuffs() {
        CookieProfile p = TestSupport.fresh();
        GoldenCookieReward frenzy = engine.rewardOfType(p, GoldenRewardType.FRENZY, T0);
        assertNotNull(frenzy.buff());
        assertEquals(BuffType.FRENZY, frenzy.buff().type());
        assertEquals(7.0, frenzy.buff().multiplier());
        assertEquals(T0.plusSeconds(30), frenzy.buff().expiresAt());
        assertTrue(frenzy.cookies().isZero());

        p.setPrestigeUpgradeLevel("lasting_glow", 5); // +50% duration
        GoldenCookieReward click = engine.rewardOfType(p, GoldenRewardType.CLICK_FRENZY, T0);
        assertEquals(BuffType.CLICK_FRENZY, click.buff().type());
        assertEquals(777.0, click.buff().multiplier());
        assertEquals(T0.plus(Duration.ofMillis(19_500)), click.buff().expiresAt());
    }

    @Test
    void applyRewardCreditsCookiesOrBuffAndCountsGoldenClick() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("2000"));
        p.markClean();
        engine.applyGoldenReward(p, engine.rewardOfType(p, GoldenRewardType.CHAIN_BONUS, T0), T0);
        assertAmount("2100", p.cookies());
        assertAmount("2100", p.lifetimeCookies());
        assertEquals(1, p.goldenCookiesClicked());
        assertTrue(p.isDirty());

        engine.applyGoldenReward(p, engine.rewardOfType(p, GoldenRewardType.FRENZY, T0), T0);
        assertEquals(1, p.activeBuffs().size());
        assertEquals(2, p.goldenCookiesClicked());
        assertEquals(7.0, engine.compute(p).buffCpsMultiplier());
        engine.expireBuffs(p, T0.plusSeconds(31));
        assertTrue(p.activeBuffs().isEmpty());
    }

    @Test
    void rewardTypeSelectionUsesWeights() {
        CookieProfile p = TestSupport.fresh();
        // weights LUCKY 45, FRENZY 35, CLICK_FRENZY 10, CHAIN 10 (enum order)
        assertEquals(GoldenRewardType.LUCKY, engine.rewardFor(p, fixed(0, 0), T0).type());
        assertEquals(GoldenRewardType.LUCKY, engine.rewardFor(p, fixed(0, 44), T0).type());
        assertEquals(GoldenRewardType.FRENZY, engine.rewardFor(p, fixed(0, 45), T0).type());
        assertEquals(GoldenRewardType.CLICK_FRENZY, engine.rewardFor(p, fixed(0, 80), T0).type());
        assertEquals(GoldenRewardType.CHAIN_BONUS, engine.rewardFor(p, fixed(0, 99), T0).type());
        // all types show up with a real RNG
        Set<GoldenRewardType> seen = EnumSet.noneOf(GoldenRewardType.class);
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) seen.add(engine.rewardFor(p, rng, T0).type());
        assertEquals(4, seen.size());
    }

    @Test
    void customWeightsCanDisableTypes() {
        CookieEngine e = TestSupport.engine(CookieBalancing.builder()
                .goldenRewardWeights(Map.of(GoldenRewardType.FRENZY, 1)).build());
        CookieProfile p = TestSupport.fresh();
        Random rng = new Random(1);
        for (int i = 0; i < 50; i++) assertEquals(GoldenRewardType.FRENZY, e.rewardFor(p, rng, T0).type());
    }
}
