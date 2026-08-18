package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieBalancingTest {

    @Test
    void defaultsMatchSpecification() {
        CookieBalancing b = CookieBalancing.defaults();
        assertEquals(1.15, b.costGrowth());
        assertEquals(List.of(1.0, 1.1, 1.25, 1.5, 2.0), b.comboStages());
        assertEquals(800, b.comboWindowMillis());
        assertEquals(3000, b.comboDecayMillis());
        assertEquals(15, b.maxClicksPerSecond());
        assertTrue(b.offlineEnabled());
        assertEquals(8 * 3600, b.offlineMaxSeconds());
        assertEquals(0.5, b.offlineEfficiency());
        assertEquals(15 * 60, b.specialMinIntervalSeconds());
        assertEquals(120 * 60, b.specialMaxIntervalSeconds());
        assertEquals(5 * 60, b.specialFloorIntervalSeconds());
        assertEquals(45, b.specialLifetimeSeconds());
        assertEquals(60, b.specialClickClampCpsSeconds());
        assertEquals(4, b.maxComboStage());
        assertEquals(2.0, b.comboMultiplier(99));
        assertEquals(1.0, b.comboMultiplier(-1));
    }

    @Test
    void everyRarityIsBalancedAndTheRewardTypesFollowTheDesignTable() {
        CookieBalancing b = CookieBalancing.defaults();
        assertEquals(SpecialCookieRarity.values().length, b.rarities().size());
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            SpecialCookieTuning t = b.tuning(rarity);
            assertEquals(rarity.baseWeight(), t.weight());
            assertEquals(100, t.rewardWeights().values().stream().mapToInt(Integer::intValue).sum(), rarity + " reward weights");
            assertTrue(t.rewardWeight(GoldenRewardType.LUCKY) > 0);
            assertTrue(t.rewardWeight(GoldenRewardType.FRENZY) > 0);
            assertTrue(t.rewardWeight(GoldenRewardType.CLICK_FRENZY) > 0);
        }
        assertEquals(0, b.tuning(SpecialCookieRarity.SILVER).rewardWeight(GoldenRewardType.CHAIN_BONUS));
        assertEquals(10, b.tuning(SpecialCookieRarity.GOLDEN).rewardWeight(GoldenRewardType.CHAIN_BONUS));
        assertEquals(0, b.tuning(SpecialCookieRarity.PLATINUM).rewardWeight(GoldenRewardType.BLESSING));
        assertEquals(15, b.tuning(SpecialCookieRarity.DIAMOND).rewardWeight(GoldenRewardType.BLESSING));
        assertEquals(25, b.tuning(SpecialCookieRarity.MASTER).rewardWeight(GoldenRewardType.BLESSING));
        // magnitudes rise strictly with the rarity
        double frenzy = 0;
        double clickFrenzy = 0;
        double bankFraction = 0;
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            SpecialCookieTuning t = b.tuning(rarity);
            assertTrue(t.frenzyMultiplier() > frenzy, rarity + " frenzy");
            assertTrue(t.clickFrenzyMultiplier() > clickFrenzy, rarity + " click frenzy");
            assertTrue(t.luckyBankFraction() > bankFraction, rarity + " lucky bank fraction");
            frenzy = t.frenzyMultiplier();
            clickFrenzy = t.clickFrenzyMultiplier();
            bankFraction = t.luckyBankFraction();
        }
        assertEquals(77.0, b.tuning(SpecialCookieRarity.MASTER).blessingCpsMultiplier());
        assertEquals(250.0, b.tuning(SpecialCookieRarity.MASTER).blessingClickMultiplier());
        assertEquals(45, b.tuning(SpecialCookieRarity.MASTER).blessingSeconds());
        // every instant reward is bounded by a window of unbuffed CPS, chain bonuses included
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            SpecialCookieTuning t = b.tuning(rarity);
            if (t.rewardWeight(GoldenRewardType.CHAIN_BONUS) > 0) {
                assertTrue(t.chainCpsSeconds() > 0, rarity + " chain bonus must be capped");
            }
        }
    }

    @Test
    void builderRoundTripAndValidation() {
        CookieBalancing custom = CookieBalancing.defaults().toBuilder().costGrowth(1.2).offlineEnabled(false).build();
        assertEquals(1.2, custom.costGrowth());
        assertFalse(custom.offlineEnabled());
        assertEquals(CookieBalancing.defaults(), custom.toBuilder().costGrowth(1.15).offlineEnabled(true).build());

        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().costGrowth(1.0).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().comboStages(List.of(1.5, 2.0)).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().comboStages(List.of(1.0, 2.0, 1.5)).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().comboDecayMillis(100).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().maxClicksPerSecond(0).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().offlineEfficiency(1.5).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().specialMinIntervalSeconds(0).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder()
                .specialMinIntervalSeconds(600).specialMaxIntervalSeconds(300).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().specialFloorIntervalSeconds(0).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().specialLifetimeSeconds(0).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder()
                .rarities(Map.of(SpecialCookieRarity.SILVER, CookieBalancing.defaults().tuning(SpecialCookieRarity.SILVER))).build());
    }

    @Test
    void treeNodeCostsDoubleAndSaturate() {
        PrestigeTreeNode node = PrestigeTreeNode.of("n", 100, 3, PrestigeTreeEffectType.GLOBAL_CPS_PERCENT, 5);
        assertEquals(3, node.costForLevel(0));
        assertEquals(6, node.costForLevel(1));
        assertEquals(3L << 20, node.costForLevel(20));
        assertEquals(Long.MAX_VALUE, node.costForLevel(70));
        assertEquals(25.0, node.totalValue(5));
        assertEquals(500.0, node.totalValue(1000)); // clamped to maxLevel
        assertThrows(IllegalArgumentException.class, () -> node.costForLevel(-1));
        assertThrows(IllegalArgumentException.class, () ->
                new PrestigeTreeNode("x", "k", 1, 1, PrestigeTreeEffectType.STARTING_GENERATORS, 1, null));
    }

    @Test
    void generatorMilestoneHelpers() {
        GeneratorDefinition g = DefaultCatalog.generators().getFirst();
        assertEquals(0, g.milestonesReached(9));
        assertEquals(1, g.milestonesReached(10));
        assertEquals(5, g.milestonesReached(10_000));
        assertEquals(10, g.nextMilestone(0));
        assertEquals(25, g.nextMilestone(10));
        assertEquals(-1, g.nextMilestone(200));
    }
}
