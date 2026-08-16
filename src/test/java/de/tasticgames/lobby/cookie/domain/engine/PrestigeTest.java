package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeDefinition;
import de.tasticgames.lobby.cookie.domain.model.ActiveBuff;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.NodePurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.PrestigeCheck;
import de.tasticgames.lobby.cookie.domain.model.PrestigePlan;
import de.tasticgames.lobby.cookie.domain.prestige.PrestigeCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.tasticgames.lobby.cookie.domain.TestSupport.PLAYER;
import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrestigeTest {

    private final CookieEngine engine = TestSupport.engine();
    private final CookieCatalog catalog = engine.catalog();

    /** A "lived-in" profile at the given level with lifetime cookies set explicitly. */
    private CookieProfile livedIn(int level, BigDecimal lifetime) {
        long earned = PrestigeCalculator.crumbsFor(catalog.requirePrestige(level).requiredLifetimeCookies());
        CookieProfile p = CookieProfile.builder(PLAYER)
                .prestigeLevel(level)
                .cookies(CookieAmount.of("12345"))
                .lifetimeCookies(CookieAmount.of(lifetime))
                .crumbs(earned)
                .crumbsEarnedTotal(earned)
                .totalClicks(777)
                .goldenCookiesClicked(3)
                .playtimeSeconds(999)
                .highestCombo(3)
                .generators(Map.of("cursor", 30, "baker", 12))
                .upgrades(Set.of("reinforced_index_finger", "cursor_tier_1"))
                .prestigeUpgrades(Map.of("efficient_ovens", 1))
                .achievements(Set.of("first_cookie"))
                .discoveredZones(Set.of("bakery_square"))
                .lastActiveAt(T0)
                .build();
        p.addBuff(ActiveBuff.of(BuffType.FRENZY, 7, T0, Duration.ofSeconds(30), "golden"));
        p.setComboState(3, 2, T0.toEpochMilli());
        p.markClean();
        return p;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void prestigeMatrix(int level) {
        PrestigeDefinition next = catalog.requirePrestige(level + 1);
        BigDecimal threshold = next.requiredLifetimeCookies();

        // ---- below threshold: denied
        CookieProfile below = livedIn(level, threshold.subtract(BigDecimal.ONE));
        PrestigeCheck deniedCheck = engine.canPrestige(below);
        assertFalse(deniedCheck.eligible());
        assertEquals(level, deniedCheck.currentLevel());
        assertEquals(level + 1, deniedCheck.nextLevel());
        assertEquals(CookieAmount.ONE, deniedCheck.missing());
        assertFalse(deniedCheck.atMaxLevel());
        PrestigePlan deniedPlan = engine.planPrestige(below);
        assertFalse(deniedPlan.eligible());
        assertEquals(level, deniedPlan.toLevel());
        assertThrows(IllegalStateException.class, () -> engine.applyPrestige(below, deniedPlan));
        assertEquals(level, below.prestigeLevel());
        assertFalse(below.isDirty());

        // ---- exactly at threshold: allowed
        CookieProfile p = livedIn(level, threshold);
        PrestigeCheck check = engine.canPrestige(p);
        assertTrue(check.eligible());
        assertEquals(CookieAmount.ZERO, check.missing());
        assertEquals(CookieAmount.of(threshold), check.requiredLifetime());

        long crumbsBefore = p.crumbs();
        long expectedGain = PrestigeCalculator.crumbsFor(threshold) - p.crumbsEarnedTotal();
        assertTrue(expectedGain > 0, "level " + level + " should earn crumbs");

        PrestigePlan plan = engine.planPrestige(p);
        assertTrue(plan.eligible());
        assertEquals(level, plan.fromLevel());
        assertEquals(level + 1, plan.toLevel());
        assertEquals(expectedGain, plan.crumbsGained());
        assertEquals(crumbsBefore + expectedGain, plan.crumbsAfter());
        assertEquals(next.totalMultiplier(), plan.newMultiplier());
        assertEquals(next.rewardCosmeticIds(), plan.rewardCosmeticIds());
        assertEquals(next.unlockedZoneId(), plan.unlockedZoneId());
        List<String> expectedGenerators = catalog.generators().stream()
                .filter(g -> g.unlockPrestige() == level + 1).map(GeneratorDefinition::id).toList();
        assertEquals(expectedGenerators, plan.unlockedGeneratorIds());
        assertEquals(PrestigePlan.KEEPS, plan.keeps());
        assertEquals(PrestigePlan.RESETS, plan.resets());
        // planning does not mutate
        assertEquals(level, p.prestigeLevel());
        assertEquals(crumbsBefore, p.crumbs());
        assertEquals(30, p.generatorCount("cursor"));
        assertFalse(p.isDirty());

        PrestigePlan applied = engine.applyPrestige(p, plan);
        assertEquals(level + 1, p.prestigeLevel());
        assertEquals(level + 1, applied.toLevel());
        assertEquals(expectedGain, applied.crumbsGained());
        // resets
        assertEquals(CookieAmount.ZERO, p.cookies());
        assertTrue(p.generators().isEmpty());
        assertTrue(p.upgrades().isEmpty());
        assertTrue(p.activeBuffs().isEmpty());
        assertEquals(0, p.comboStage());
        assertEquals(0, p.clickHistory().size());
        // keeps
        assertEquals(CookieAmount.of(threshold), p.lifetimeCookies());
        assertEquals(crumbsBefore + expectedGain, p.crumbs());
        assertEquals(PrestigeCalculator.crumbsFor(threshold), p.crumbsEarnedTotal());
        assertEquals(1, p.prestigeUpgradeLevel("efficient_ovens"));
        assertTrue(p.achievements().contains("first_cookie"));
        assertTrue(p.discoveredZones().contains("bakery_square"));
        assertEquals(777, p.totalClicks());
        assertEquals(3, p.goldenCookiesClicked());
        assertEquals(999, p.playtimeSeconds());
        assertEquals(3, p.highestCombo());
        assertTrue(p.isDirty());
        // multiplier and unlocks are live
        assertEquals(next.totalMultiplier(), engine.compute(p).prestigeMultiplier());
        assertTrue(engine.canEnter(p, next.unlockedZoneId()).allowed());
        for (String g : expectedGenerators) assertTrue(engine.isGeneratorUnlocked(p, catalog.requireGenerator(g)));
        for (GeneratorDefinition g : catalog.generators()) {
            if (g.unlockPrestige() > level + 1) assertFalse(engine.isGeneratorUnlocked(p, g), g.id());
        }
        // rewards are granted once: the same plan cannot be applied twice
        assertThrows(IllegalStateException.class, () -> engine.applyPrestige(p, plan));
        // and immediately re-prestiging is impossible without new cookies (unless already above next threshold)
        PrestigeCheck after = engine.canPrestige(p);
        if (level + 1 < catalog.maxPrestigeLevel()) {
            assertFalse(after.eligible());
        } else {
            assertTrue(after.atMaxLevel());
        }
    }

    @Test
    void prestigeTenIsFinal() {
        CookieProfile p = livedIn(10, new BigDecimal("1e50"));
        PrestigeCheck check = engine.canPrestige(p);
        assertFalse(check.eligible());
        assertTrue(check.atMaxLevel());
        assertEquals(-1, check.nextLevel());
        assertEquals(10, check.currentLevel());
        PrestigePlan plan = engine.planPrestige(p);
        assertFalse(plan.eligible());
        assertThrows(IllegalStateException.class, () -> engine.applyPrestige(p, plan));
        assertEquals(10, p.prestigeLevel());
        assertFalse(engine.prestige(p).eligible());
        assertEquals(10, p.prestigeLevel());
        assertEquals(20.0, engine.compute(p).prestigeMultiplier());
    }

    @Test
    void crumbsAreNotReEarned() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("8e6"));                 // cbrt(8) = 2 crumbs
        PrestigePlan first = engine.prestige(p);
        assertTrue(first.eligible());
        assertEquals(2, first.crumbsGained());
        assertEquals(2, p.crumbs());
        assertEquals(2, p.crumbsEarnedTotal());

        p.earn(CookieAmount.of("1e9").minus(CookieAmount.of("8e6")));  // lifetime = 1e9 → 10 crumbs total
        PrestigePlan second = engine.prestige(p);
        assertTrue(second.eligible());
        assertEquals(8, second.crumbsGained());
        assertEquals(10, p.crumbs());
        assertEquals(10, p.crumbsEarnedTotal());
        assertEquals(2, p.prestigeLevel());

        // a profile that somehow already banked more crumbs than the formula yields gains nothing
        CookieProfile rich = CookieProfile.builder(PLAYER).prestigeLevel(2).lifetimeCookies(CookieAmount.of("1e12"))
                .crumbs(0).crumbsEarnedTotal(500).build();
        PrestigePlan third = engine.planPrestige(rich);
        assertTrue(third.eligible());
        assertEquals(0, third.crumbsGained());
    }

    @Test
    void stalePlanIsRejected() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("1e6"));
        PrestigePlan plan = engine.planPrestige(p);
        engine.applyPrestige(p, plan);
        assertThrows(IllegalStateException.class, () -> engine.applyPrestige(p, plan));
        // plan for level 0 applied to a level 1 profile
        CookieProfile other = TestSupport.fresh();
        other.earn(CookieAmount.of("1e6"));
        PrestigePlan otherPlan = engine.planPrestige(other);
        other.setPrestigeLevel(1);
        assertThrows(IllegalStateException.class, () -> engine.applyPrestige(other, otherPlan));
    }

    @Test
    void newRunStartsWithTreeBonuses() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of("1e6"));
        p.setPrestigeUpgradeLevel("head_start", 3);      // 3000 starting cookies
        p.setPrestigeUpgradeLevel("helping_hands", 2);   // 2 cursors
        PrestigePlan plan = engine.planPrestige(p);
        assertEquals(CookieAmount.of(3000), plan.startingCookies());
        assertEquals(Map.of("cursor", 2), plan.startingGenerators());
        engine.applyPrestige(p, plan);
        assertEquals(CookieAmount.of(3000), p.cookies());
        assertEquals(2, p.generatorCount("cursor"));
        assertEquals(CookieAmount.of("1e6"), p.lifetimeCookies());
    }

    @Test
    void prestigeTreePurchases() {
        CookieProfile p = TestSupport.fresh();
        assertEquals(NodePurchaseResult.Reason.UNKNOWN_NODE, engine.buyPrestigeNode(p, "nope").reason());
        assertEquals(NodePurchaseResult.Reason.INSUFFICIENT_CRUMBS, engine.buyPrestigeNode(p, "iron_fingers").reason());
        p.addCrumbs(8);
        NodePurchaseResult r1 = engine.buyPrestigeNode(p, "iron_fingers");   // cost 1
        assertTrue(r1.success());
        assertEquals(1, r1.newLevel());
        assertEquals(1, r1.crumbsSpent());
        assertEquals(7, r1.crumbsAfter());
        NodePurchaseResult r2 = engine.buyPrestigeNode(p, "iron_fingers");   // cost 2
        assertEquals(2, r2.crumbsSpent());
        NodePurchaseResult r3 = engine.buyPrestigeNode(p, "iron_fingers");   // cost 4
        assertEquals(4, r3.crumbsSpent());
        assertEquals(1, p.crumbs());
        assertEquals(3, p.prestigeUpgradeLevel("iron_fingers"));
        assertEquals(NodePurchaseResult.Reason.INSUFFICIENT_CRUMBS, engine.buyPrestigeNode(p, "iron_fingers").reason());
        assertEquals(1.3, engine.compute(p).treeClickMultiplier(), 1e-12);
        // max level
        p.addCrumbs(1_000_000);
        for (int i = 3; i < 10; i++) assertTrue(engine.buyPrestigeNode(p, "iron_fingers").success());
        NodePurchaseResult max = engine.buyPrestigeNode(p, "iron_fingers");
        assertFalse(max.success());
        assertEquals(NodePurchaseResult.Reason.MAX_LEVEL, max.reason());
        assertEquals(10, p.prestigeUpgradeLevel("iron_fingers"));
        assertEquals(2, engine.nodeCost(p, catalog.requireTreeNode("night_bakers"))); // base 2 at level 0
        assertEquals(1024, catalog.requireTreeNode("iron_fingers").costForLevel(10));
    }

    @Test
    void crumbFormula() {
        assertEquals(0, PrestigeCalculator.crumbsFor(CookieAmount.ZERO));
        assertEquals(0, PrestigeCalculator.crumbsFor(CookieAmount.of("999999")));
        assertEquals(1, PrestigeCalculator.crumbsFor(CookieAmount.of("1e6")));
        assertEquals(1, PrestigeCalculator.crumbsFor(CookieAmount.of("7999999")));
        assertEquals(2, PrestigeCalculator.crumbsFor(CookieAmount.of("8e6")));
        assertEquals(10, PrestigeCalculator.crumbsFor(CookieAmount.of("1e9")));
        assertEquals(100, PrestigeCalculator.crumbsFor(CookieAmount.of("1e12")));
        assertEquals(1_000_000_000L, PrestigeCalculator.crumbsFor(CookieAmount.of("1e33")));
        assertEquals(10_000_000_000L, PrestigeCalculator.crumbsFor(CookieAmount.of("1e36")));
        assertEquals(PrestigeCalculator.MAX_CRUMBS, PrestigeCalculator.crumbsFor(CookieAmount.of("1e60")));
        assertEquals(5, PrestigeCalculator.crumbsGained(CookieAmount.of("1e9"), 5));
        assertEquals(0, PrestigeCalculator.crumbsGained(CookieAmount.of("1e9"), 50));
    }
}
