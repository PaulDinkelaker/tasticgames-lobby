package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.catalog.SpecialCookieTuning;
import de.tasticgames.lobby.cookie.domain.model.ActiveBuff;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieReward;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRoll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static de.tasticgames.lobby.cookie.domain.TestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialCookieTest {

    private final CookieEngine engine = TestSupport.engine();

    /** RNG stub returning fixed values. */
    private static RandomGenerator fixed(double d, int i) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public double nextDouble() { return d; }
            @Override public int nextInt(int bound) { return Math.min(i, bound - 1); }
        };
    }

    /** Profile that has everything unlocked and produces enough CPS for the reward comparisons. */
    private static CookieProfile master(String bank) {
        CookieProfile p = TestSupport.profileAt(7, bank);
        p.setGenerators(Map.of("reality_forge", 100)); // 1e9 base CPS
        return p;
    }

    // ------------------------------------------------------------------ payout safety rails

    @Test
    void aSpecialClickBuffCanNeverPayMoreThanTheClampPerClick() {
        CookieProfile p = master("0");
        p.addUpgrade("thousand_fingers");   // clicks gain 1 % of CPS ...
        p.addUpgrade("million_fingers");    // ... twice, the endgame click build
        BigDecimal unbuffedCps = engine.compute(p).unbuffedCps();
        BigDecimal clamp = unbuffedCps.multiply(BigDecimal.valueOf(engine.balancing().specialClickClampCpsSeconds()));
        BigDecimal unclamped = engine.compute(p).baseClickValue()
                .multiply(BigDecimal.valueOf(engine.balancing().tuning(SpecialCookieRarity.MASTER).clickFrenzyMultiplier()));
        assertTrue(unclamped.compareTo(clamp) > 0, "test setup: the buff must exceed the clamp to prove anything");

        // MASTER click frenzy (x7777) is worth ~155 s of CPS per click without the clamp
        engine.applySpecialReward(p, engine.rewardOfType(p, SpecialCookieRarity.MASTER, GoldenRewardType.CLICK_FRENZY, T0), T0);
        BigDecimal buffed = engine.click(p, T0.plusMillis(200)).reward().toBigDecimal();
        assertTrue(buffed.compareTo(clamp) <= 0, "clamped click paid " + buffed + " > " + clamp);
        assertTrue(buffed.compareTo(clamp.multiply(new BigDecimal("0.99"))) > 0, "the clamp should be the binding limit here");

        // the clamp never reduces a click below its unbuffed value (early game, no production yet)
        CookieProfile fresh = TestSupport.profileAt(1, "0");
        engine.applySpecialReward(fresh, engine.rewardOfType(fresh, SpecialCookieRarity.SILVER, GoldenRewardType.CLICK_FRENZY, T0), T0);
        assertTrue(engine.click(fresh, T0.plusMillis(200)).reward().toBigDecimal().signum() > 0);
    }

    @Test
    void clickRewardsAreNotDrawnWhereNoCookieCanBeClicked() {
        CookieProfile p = master("1e9");
        // GOLDEN weights: LUCKY 45, FRENZY 35, CLICK_FRENZY 10, CHAIN 10 – roll 80 lands on CLICK_FRENZY
        assertEquals(GoldenRewardType.CLICK_FRENZY, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 80), T0, true).type());
        // without a clickable cookie (open world) the draw renormalises over the remaining types
        for (int roll = 0; roll < 90; roll++) {
            assertTrue(engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, roll), T0, false).type() != GoldenRewardType.CLICK_FRENZY,
                    "click reward drawn without a clickable cookie at roll " + roll);
        }
    }

    // ------------------------------------------------------------------ rarity gating

    @Test
    void raritiesUnlockWithPrestige() {
        assertEquals(List.of(), engine.unlockedRarities(TestSupport.profileAt(0, "1e9")));
        assertEquals(List.of(SpecialCookieRarity.SILVER, SpecialCookieRarity.GOLDEN),
                engine.unlockedRarities(TestSupport.profileAt(1, "1e9")));
        assertEquals(List.of(SpecialCookieRarity.SILVER, SpecialCookieRarity.GOLDEN, SpecialCookieRarity.PLATINUM),
                engine.unlockedRarities(TestSupport.profileAt(2, "1e9")));
        assertEquals(3, engine.unlockedRarities(TestSupport.profileAt(3, "1e9")).size());
        assertEquals(4, engine.unlockedRarities(TestSupport.profileAt(4, "1e9")).size());
        assertEquals(4, engine.unlockedRarities(TestSupport.profileAt(6, "1e9")).size());
        assertEquals(List.of(SpecialCookieRarity.values()), engine.unlockedRarities(TestSupport.profileAt(7, "1e9")));
        assertEquals(5, engine.unlockedRarities(TestSupport.profileAt(10, "1e9")).size());
    }

    @Test
    void prestigeZeroDrawsNoSpecialCookieAtAll() {
        CookieProfile p = TestSupport.profileAt(0, "1e9");
        assertTrue(engine.rarityWeights(p).isEmpty());
        assertTrue(engine.rarityChances(p).isEmpty());
        assertTrue(engine.rollRarity(p, fixed(0.5, 0)).isEmpty());
        assertTrue(engine.rollSpecial(p, fixed(0.5, 0), T0).isEmpty());
    }

    // ------------------------------------------------------------------ weights

    @Test
    void weightsAreNormalisedOverTheUnlockedRarities() {
        Map<SpecialCookieRarity, Double> p1 = engine.rarityChances(TestSupport.profileAt(1, "1e9"));
        assertEquals(2, p1.size());
        assertEquals(60.0 / 90.0, p1.get(SpecialCookieRarity.SILVER), 1e-12);
        assertEquals(30.0 / 90.0, p1.get(SpecialCookieRarity.GOLDEN), 1e-12);

        Map<SpecialCookieRarity, Double> p7 = engine.rarityChances(TestSupport.profileAt(7, "1e9"));
        assertEquals(5, p7.size());
        assertEquals(60.0 / 101.5, p7.get(SpecialCookieRarity.SILVER), 1e-12);
        assertEquals(7.0 / 101.5, p7.get(SpecialCookieRarity.PLATINUM), 1e-12);
        assertEquals(1.5 / 101.5, p7.get(SpecialCookieRarity.MASTER), 1e-12);
        assertEquals(1.0, p7.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
    }

    @Test
    void upgradesAndTreeNodeShiftTheWeights() {
        CookieProfile p = TestSupport.profileAt(7, "1e9");
        p.addUpgrade("refined_sugar");                   // GOLDEN x1.5
        p.addUpgrade("master_recipe_book");              // MASTER x2
        p.setPrestigeUpgradeLevel("connoisseur", 5);     // +50% for PLATINUM and above
        Map<SpecialCookieRarity, Double> weights = engine.rarityWeights(p);
        assertEquals(60.0, weights.get(SpecialCookieRarity.SILVER), 1e-12);
        assertEquals(45.0, weights.get(SpecialCookieRarity.GOLDEN), 1e-12);
        assertEquals(10.5, weights.get(SpecialCookieRarity.PLATINUM), 1e-12);
        assertEquals(4.5, weights.get(SpecialCookieRarity.DIAMOND), 1e-12);  // 3.0 x 1.5
        assertEquals(4.5, weights.get(SpecialCookieRarity.MASTER), 1e-12); // 1.5 x 2 x 1.5
        // the stats expose the same multipliers so the UI can show the real chances
        assertEquals(1.0, engine.compute(p).specialWeightMultiplier(SpecialCookieRarity.SILVER), 1e-12);
        assertEquals(1.5, engine.compute(p).specialWeightMultiplier(SpecialCookieRarity.GOLDEN), 1e-12);
        assertEquals(3.0, engine.compute(p).specialWeightMultiplier(SpecialCookieRarity.MASTER), 1e-12);
        double total = 60 + 45 + 10.5 + 4.5 + 4.5;
        assertEquals(4.5 / total, engine.rarityChances(p).get(SpecialCookieRarity.MASTER), 1e-12);
        // the connoisseur node never touches the two common rarities
        assertEquals(60.0 / total, engine.rarityChances(p).get(SpecialCookieRarity.SILVER), 1e-12);
    }

    @Test
    void rarityDrawUsesTheWeightsDeterministically() {
        CookieProfile p = TestSupport.profileAt(7, "1e9"); // 60 / 30 / 7 / 2.5 / 0.5
        assertEquals(SpecialCookieRarity.SILVER, engine.rollRarity(p, fixed(0.0, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.SILVER, engine.rollRarity(p, fixed(0.5, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.GOLDEN, engine.rollRarity(p, fixed(0.7, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.PLATINUM, engine.rollRarity(p, fixed(0.95, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.DIAMOND, engine.rollRarity(p, fixed(0.98, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.MASTER, engine.rollRarity(p, fixed(0.999, 0)).orElseThrow());
        // a boost moves the boundary: with MASTER at weight 1.5 of 76.5 (P2 profile is unaffected)
        CookieProfile boosted = TestSupport.profileAt(1, "1e9");
        boosted.addUpgrade("refined_sugar"); // GOLDEN 30 -> 45, so SILVER covers 60/105
        assertEquals(SpecialCookieRarity.SILVER, engine.rollRarity(boosted, fixed(0.55, 0)).orElseThrow());
        assertEquals(SpecialCookieRarity.GOLDEN, engine.rollRarity(boosted, fixed(0.6, 0)).orElseThrow());
        // every rarity shows up with a real RNG
        Set<SpecialCookieRarity> seen = EnumSet.noneOf(SpecialCookieRarity.class);
        Random rng = new Random(42);
        for (int i = 0; i < 20_000; i++) engine.rollRarity(p, rng).ifPresent(seen::add);
        assertEquals(5, seen.size());
    }

    // ------------------------------------------------------------------ scheduling

    @Test
    void nextSpawnIsDrawnFromTheIntervalAndDividedByTheChanceMultiplier() {
        CookieProfile p = TestSupport.profileAt(1, "1e9");
        assertEquals(T0.plusSeconds(900), engine.scheduleNextSpecial(p, fixed(0.0, 0), T0));
        assertEquals(T0.plusSeconds(7200), engine.scheduleNextSpecial(p, fixed(1.0, 0), T0));
        assertEquals(T0.plusSeconds(4050), engine.scheduleNextSpecial(p, fixed(0.5, 0), T0));

        p.addUpgrade("golden_luck");                       // x1.5
        p.setPrestigeUpgradeLevel("lucky_charms", 5);      // +50% -> x2.25 in total
        assertEquals(2.25, engine.compute(p).goldenChanceMultiplier(), 1e-12);
        assertEquals(T0.plusSeconds(400), engine.scheduleNextSpecial(p, fixed(0.0, 0), T0));
        assertEquals(T0.plusSeconds(3200), engine.scheduleNextSpecial(p, fixed(1.0, 0), T0));
    }

    @Test
    void drawnInstantsStayWithinTheBoundsAndNeverBelowTheFloor() {
        CookieBalancing balancing = CookieBalancing.defaults();
        CookieProfile plain = TestSupport.profileAt(1, "1e9");
        CookieProfile lucky = TestSupport.profileAt(1, "1e9");
        lucky.addUpgrade("golden_luck");
        lucky.setPrestigeUpgradeLevel("lucky_charms", 5);
        Random rng = new Random(7);
        for (CookieProfile p : List.of(plain, lucky)) {
            double chance = engine.compute(p).goldenChanceMultiplier();
            long lower = (long) Math.max(balancing.specialFloorIntervalSeconds(), balancing.specialMinIntervalSeconds() / chance);
            long upper = (long) Math.ceil(Math.max(balancing.specialFloorIntervalSeconds(), balancing.specialMaxIntervalSeconds() / chance));
            for (int i = 0; i < 2_000; i++) {
                long seconds = Duration.between(T0, engine.scheduleNextSpecial(p, rng, T0)).getSeconds();
                assertTrue(seconds >= lower, "drawn " + seconds + "s is below " + lower + "s");
                assertTrue(seconds <= upper, "drawn " + seconds + "s is above " + upper + "s");
                assertTrue(seconds >= balancing.specialFloorIntervalSeconds() - 1, "drawn " + seconds + "s is below the floor");
            }
        }
        // a luckier player waits strictly less on the same draw
        assertTrue(engine.scheduleNextSpecial(lucky, fixed(0.5, 0), T0).isBefore(engine.scheduleNextSpecial(plain, fixed(0.5, 0), T0)));
    }

    @Test
    void theFloorCapsEvenAnAbsurdChanceMultiplier() {
        CookieEngine e = TestSupport.engine(CookieBalancing.defaults().toBuilder()
                .specialMinIntervalSeconds(60).specialMaxIntervalSeconds(120).specialFloorIntervalSeconds(300).build());
        CookieProfile p = TestSupport.profileAt(1, "1e9");
        p.addUpgrade("golden_luck");
        p.setPrestigeUpgradeLevel("lucky_charms", 5);
        assertEquals(T0.plusSeconds(300), e.scheduleNextSpecial(p, fixed(0.0, 0), T0));
        assertEquals(T0.plusSeconds(300), e.scheduleNextSpecial(p, fixed(1.0, 0), T0));
    }

    @Test
    void reconnectingCannotShortenTheWaitBelowTheFloor() {
        // the timer is per session: a relog redraws a full interval, so the shortest possible wait after
        // any number of relogs is still the drawn minimum (never the remainder of the previous wait)
        CookieProfile p = TestSupport.profileAt(1, "1e9");
        p.addUpgrade("golden_luck");
        p.setPrestigeUpgradeLevel("lucky_charms", 5);
        Random rng = new Random(11);
        Instant now = T0;
        for (int relog = 0; relog < 500; relog++) {
            long seconds = Duration.between(now, engine.scheduleNextSpecial(p, rng, now)).getSeconds();
            assertTrue(seconds >= 400, "relog produced a " + seconds + "s wait, shorter than min/chance");
            now = now.plusSeconds(1);
        }
    }

    @Test
    void aDueCookieCarriesTheRarityAndTheSpawnLifetime() {
        SpecialCookieRoll roll = engine.rollSpecial(TestSupport.profileAt(1, "1e9"), fixed(0.0, 0), T0).orElseThrow();
        assertEquals(SpecialCookieRarity.SILVER, roll.rarity());
        assertEquals(T0, roll.scheduledFor());
        assertEquals(T0.plusSeconds(45), roll.expiresAt());
    }

    // ------------------------------------------------------------------ rewards

    @Test
    void luckyRewardIsMinOfBankShareAndCpsWindowPlusFlat() {
        CookieProfile p = TestSupport.profileAt(1, "10000");
        p.setGenerators(Map.of("cursor", 10)); // 10*0.05*2 = 1 base cps, x1.25 (P1) = 1.25 cps
        // 15 min of CPS = 1125 < 15% of 10000 = 1500
        SpecialCookieReward r = engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.LUCKY, T0);
        assertAmount("1138", r.cookies());
        assertTrue(r.isInstant());
        // with a big bank the cps window dominates; with a small bank the bank share dominates
        p.setGenerators(Map.of("oven", 200)); // 200*2.5*32*1.25 = 20000 cps -> 15 min = 18M
        assertAmount("1513", engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.LUCKY, T0).cookies());
        // golden_glow doubles instant rewards
        p.addUpgrade("golden_glow");
        assertAmount("3026", engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.LUCKY, T0).cookies());
        // silver takes a third of the bank fraction and a fifth of the CPS window: min(500, 375) + 13
        p.clearUpgrades();
        p.setGenerators(Map.of("cursor", 10));
        assertAmount("388", engine.rewardOfType(p, SpecialCookieRarity.SILVER, GoldenRewardType.LUCKY, T0).cookies());
    }

    @Test
    void chainBonusIsBankFraction() {
        CookieProfile p = TestSupport.profileAt(2, "2000");
        assertAmount("100", engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.CHAIN_BONUS, T0).cookies());
        assertAmount("160", engine.rewardOfType(p, SpecialCookieRarity.PLATINUM, GoldenRewardType.CHAIN_BONUS, T0).cookies());
    }

    @Test
    void frenzyAndClickFrenzyProduceBuffs() {
        CookieProfile p = TestSupport.profileAt(1, "0");
        SpecialCookieReward frenzy = engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.FRENZY, T0);
        assertEquals(1, frenzy.buffs().size());
        ActiveBuff buff = frenzy.buffs().getFirst();
        assertNotNull(buff);
        assertEquals(BuffType.FRENZY, buff.type());
        assertEquals(7.0, buff.multiplier());
        assertEquals(T0.plusSeconds(30), buff.expiresAt());
        assertTrue(frenzy.cookies().isZero());
        assertEquals("special:GOLDEN:FRENZY", buff.source());

        p.setPrestigeUpgradeLevel("lasting_glow", 5); // +50% duration
        SpecialCookieReward click = engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.CLICK_FRENZY, T0);
        assertEquals(BuffType.CLICK_FRENZY, click.buffs().getFirst().type());
        assertEquals(777.0, click.buffs().getFirst().multiplier());
        assertEquals(T0.plus(Duration.ofMillis(4_500)), click.buffs().getFirst().expiresAt()); // 3 s x 1.5
    }

    @Test
    void blessingBoostsCpsAndClicksAtOnce() {
        CookieProfile p = TestSupport.profileAt(4, "0");
        p.setGenerators(Map.of("baker", 10)); // 10*0.3*2 = 6 base cps
        SpecialCookieReward blessing = engine.rewardOfType(p, SpecialCookieRarity.DIAMOND, GoldenRewardType.BLESSING, T0);
        assertEquals(2, blessing.buffs().size());
        assertEquals(30.0, blessing.cpsMultiplier());
        assertEquals(100.0, blessing.clickMultiplier());
        assertEquals(T0.plusSeconds(30), blessing.buffExpiresAt().orElseThrow());
        engine.applySpecialReward(p, blessing, T0);
        assertEquals(30.0, engine.compute(p).buffCpsMultiplier());
        assertEquals(100.0, engine.compute(p).buffClickMultiplier());
        assertEquals(0, new BigDecimal("504").compareTo(engine.compute(p).effectiveCps())); // 6 * 2.8 (P4) * 30

        SpecialCookieReward master = engine.rewardOfType(p, SpecialCookieRarity.MASTER, GoldenRewardType.BLESSING, T0);
        assertEquals(77.0, master.cpsMultiplier());
        assertEquals(250.0, master.clickMultiplier());
        assertEquals(T0.plusSeconds(45), master.buffExpiresAt().orElseThrow());
    }

    @Test
    void rewardMagnitudesGrowWithRarityAndStayFiniteForHugeBanks() {
        CookieProfile p = master("1e12");
        CookieAmount previousLucky = CookieAmount.ZERO;
        double previousFrenzy = 0;
        double previousClickFrenzy = 0;
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            CookieAmount lucky = engine.rewardOfType(p, rarity, GoldenRewardType.LUCKY, T0).cookies();
            assertTrue(lucky.compareTo(previousLucky) > 0, rarity + " lucky " + lucky + " is not above " + previousLucky);
            previousLucky = lucky;
            double frenzy = engine.rewardOfType(p, rarity, GoldenRewardType.FRENZY, T0).cpsMultiplier();
            assertTrue(frenzy > previousFrenzy, rarity + " frenzy x" + frenzy + " is not above x" + previousFrenzy);
            previousFrenzy = frenzy;
            double clickFrenzy = engine.rewardOfType(p, rarity, GoldenRewardType.CLICK_FRENZY, T0).clickMultiplier();
            assertTrue(clickFrenzy > previousClickFrenzy, rarity + " click frenzy x" + clickFrenzy);
            previousClickFrenzy = clickFrenzy;
        }
        // huge bank: the CPS window caps the lucky reward, nothing overflows
        CookieProfile rich = master("1e300");
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            CookieAmount lucky = engine.rewardOfType(rich, rarity, GoldenRewardType.LUCKY, T0).cookies();
            assertTrue(lucky.toBigDecimal().compareTo(new BigDecimal("1e20")) < 0, rarity + " lucky is not capped: " + lucky);
        }
        // a hoarded bank does not turn a chain bonus into hours of production: it is capped by the CPS window
        CookieAmount chain = engine.rewardOfType(rich, SpecialCookieRarity.PLATINUM, GoldenRewardType.CHAIN_BONUS, T0).cookies();
        BigDecimal chainCap = engine.compute(rich).unbuffedCps()
                .multiply(BigDecimal.valueOf(engine.balancing().tuning(SpecialCookieRarity.PLATINUM).chainCpsSeconds()));
        assertTrue(chain.toBigDecimal().compareTo(chainCap) <= 0, "chain bonus above the CPS cap: " + chain);
        assertTrue(chain.toBigDecimal().compareTo(new BigDecimal("8e298")) < 0, "chain bonus is not capped at all");
    }

    @Test
    void applyRewardCreditsCookiesOrBuffsAndCountsTheCookie() {
        CookieProfile p = TestSupport.profileAt(2, "2000");
        p.markClean();
        engine.applySpecialReward(p, engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.CHAIN_BONUS, T0), T0);
        assertAmount("2100", p.cookies());
        assertEquals(1, p.goldenCookiesClicked());
        assertTrue(p.isDirty());

        engine.applySpecialReward(p, engine.rewardOfType(p, SpecialCookieRarity.GOLDEN, GoldenRewardType.FRENZY, T0), T0);
        assertEquals(1, p.activeBuffs().size());
        assertEquals(2, p.goldenCookiesClicked());
        assertEquals(7.0, engine.compute(p).buffCpsMultiplier());
        engine.expireBuffs(p, T0.plusSeconds(31));
        assertTrue(p.activeBuffs().isEmpty());
    }

    @Test
    void rewardTypeSelectionUsesThePerRarityWeights() {
        CookieProfile p = TestSupport.profileAt(7, "1e9");
        // GOLDEN: LUCKY 45, FRENZY 35, CLICK_FRENZY 10, CHAIN 10 (enum order)
        assertEquals(GoldenRewardType.LUCKY, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 0), T0).type());
        assertEquals(GoldenRewardType.LUCKY, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 44), T0).type());
        assertEquals(GoldenRewardType.FRENZY, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 45), T0).type());
        assertEquals(GoldenRewardType.CLICK_FRENZY, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 80), T0).type());
        assertEquals(GoldenRewardType.CHAIN_BONUS, engine.rewardFor(p, SpecialCookieRarity.GOLDEN, fixed(0, 99), T0).type());
        // SILVER never rolls a chain bonus, MASTER never rolls one either but blesses instead
        Set<GoldenRewardType> silver = EnumSet.noneOf(GoldenRewardType.class);
        Set<GoldenRewardType> master = EnumSet.noneOf(GoldenRewardType.class);
        Random rng = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            silver.add(engine.rewardFor(p, SpecialCookieRarity.SILVER, rng, T0).type());
            master.add(engine.rewardFor(p, SpecialCookieRarity.MASTER, rng, T0).type());
        }
        assertEquals(EnumSet.of(GoldenRewardType.LUCKY, GoldenRewardType.FRENZY, GoldenRewardType.CLICK_FRENZY), silver);
        assertEquals(EnumSet.of(GoldenRewardType.LUCKY, GoldenRewardType.FRENZY, GoldenRewardType.CLICK_FRENZY, GoldenRewardType.BLESSING), master);
        assertFalse(master.contains(GoldenRewardType.CHAIN_BONUS));
    }

    @Test
    void customWeightsCanDisableTypesAndRarities() {
        CookieBalancing.Builder builder = CookieBalancing.defaults().toBuilder();
        CookieEngine e = TestSupport.engine(builder
                .rarity(SpecialCookieRarity.GOLDEN, builder.rarity(SpecialCookieRarity.GOLDEN).toBuilder()
                        .rewardWeights(Map.of(GoldenRewardType.FRENZY, 1)).build())
                .rarity(SpecialCookieRarity.SILVER, builder.rarity(SpecialCookieRarity.SILVER).toBuilder()
                        .weight(0).build())
                .build());
        CookieProfile p = TestSupport.profileAt(1, "1e9");
        Random rng = new Random(1);
        for (int i = 0; i < 50; i++) {
            assertEquals(GoldenRewardType.FRENZY, e.rewardFor(p, SpecialCookieRarity.GOLDEN, rng, T0).type());
            assertSame(SpecialCookieRarity.GOLDEN, e.rollRarity(p, rng).orElseThrow());
        }
        assertEquals(Map.of(SpecialCookieRarity.GOLDEN, 1.0), e.rarityChances(p));
    }

    @Test
    void tuningRejectsInvalidValues() {
        SpecialCookieTuning.Builder b = SpecialCookieTuning.builder(SpecialCookieRarity.SILVER);
        assertEquals(60.0, b.build().weight());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SpecialCookieTuning.builder(SpecialCookieRarity.SILVER).rewardWeights(Map.of()).build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SpecialCookieTuning.builder(SpecialCookieRarity.SILVER).rewardWeights(Map.of(GoldenRewardType.LUCKY, -1)).build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SpecialCookieTuning.builder(SpecialCookieRarity.SILVER).frenzy(0.5, 10).build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SpecialCookieTuning.builder(SpecialCookieRarity.SILVER).clickFrenzySeconds(0).build());
    }
}
