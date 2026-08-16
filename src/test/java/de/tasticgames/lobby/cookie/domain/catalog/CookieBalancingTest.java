package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
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
        assertEquals(300, b.goldenBaseIntervalSeconds());
        assertEquals(15, b.goldenLifetimeSeconds());
        assertEquals(30, b.goldenBuffSeconds());
        assertEquals(4, b.maxComboStage());
        assertEquals(2.0, b.comboMultiplier(99));
        assertEquals(1.0, b.comboMultiplier(-1));
        assertEquals(100, b.goldenRewardWeights().values().stream().mapToInt(Integer::intValue).sum());
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
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder().goldenRewardWeights(Map.of()).build());
        assertThrows(IllegalArgumentException.class, () -> CookieBalancing.builder()
                .goldenRewardWeights(Map.of(GoldenRewardType.LUCKY, -1)).build());
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
