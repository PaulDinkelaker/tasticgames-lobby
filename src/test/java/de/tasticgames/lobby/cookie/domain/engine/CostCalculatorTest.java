package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostCalculatorTest {

    private final CookieCatalog catalog = CookieCatalog.defaults();
    private final CostCalculator costs = new CostCalculator(1.15);
    private final GeneratorDefinition cursor = catalog.requireGenerator("cursor");
    private final GeneratorDefinition reality = catalog.requireGenerator("reality_forge");

    @Test
    void unitCostsAreCeilOfGeometricGrowth() {
        assertEquals(CookieAmount.of(15), costs.unitCost(cursor, 0));
        assertEquals(CookieAmount.of(18), costs.unitCost(cursor, 1));   // 17.25 → 18
        assertEquals(CookieAmount.of(20), costs.unitCost(cursor, 2));   // 19.8375 → 20
        assertEquals(CookieAmount.of(23), costs.unitCost(cursor, 3));   // 22.81 → 23
        assertEquals(CookieAmount.of(1265), costs.unitCost(catalog.requireGenerator("oven"), 1)); // 1265 exactly
        assertThrows(IllegalArgumentException.class, () -> costs.unitCost(cursor, -1));
    }

    @Test
    void bulkCostIsExactSumOfUnitPrices() {
        assertEquals(CookieAmount.of(76), costs.costFor(cursor, 0, 4));
        assertEquals(CookieAmount.ZERO, costs.costFor(cursor, 0, 0));
        for (int owned : new int[]{0, 7, 33, 120}) {
            for (int count : new int[]{1, 10, 100}) {
                BigDecimal naive = BigDecimal.ZERO;
                for (int i = 0; i < count; i++) naive = naive.add(costs.unitCost(cursor, owned + i).toBigDecimal());
                assertEquals(0, naive.compareTo(costs.costFor(cursor, owned, count).toBigDecimal()),
                        "owned=" + owned + " count=" + count);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> costs.costFor(cursor, 0, -1));
    }

    @Test
    void maxAffordableMatchesNaiveLoop() {
        for (int owned : new int[]{0, 5, 40}) {
            for (String budget : new String[]{"0", "14", "15", "32", "33", "76", "1000", "123456", "98765432", "1e12"}) {
                CookieAmount b = CookieAmount.of(budget);
                CostCalculator.Affordable a = costs.maxAffordable(cursor, owned, b);
                // naive
                int n = 0;
                BigDecimal spent = BigDecimal.ZERO;
                while (true) {
                    BigDecimal next = costs.unitCost(cursor, owned + n).toBigDecimal();
                    if (spent.add(next).compareTo(b.toBigDecimal()) > 0) break;
                    spent = spent.add(next);
                    n++;
                }
                assertEquals(n, a.count(), "owned=" + owned + " budget=" + budget);
                assertEquals(0, spent.compareTo(a.totalCost().toBigDecimal()));
                assertTrue(a.totalCost().compareTo(b) <= 0);
            }
        }
    }

    @Test
    void maxAffordableEdgeCases() {
        assertEquals(0, costs.maxAffordable(cursor, 0, CookieAmount.of(14)).count());
        assertEquals(1, costs.maxAffordable(cursor, 0, CookieAmount.of(15)).count());
        assertEquals(1, costs.maxAffordable(cursor, 0, CookieAmount.of(32)).count());
        assertEquals(2, costs.maxAffordable(cursor, 0, CookieAmount.of(33)).count());
    }

    @Test
    void handlesHugeBudgetsAndCounts() {
        CookieAmount budget = CookieAmount.of("1e40");
        CostCalculator.Affordable a = costs.maxAffordable(reality, 0, budget);
        assertTrue(a.count() > 400 && a.count() < 500, "count=" + a.count());
        assertTrue(a.totalCost().compareTo(budget) <= 0);
        // the next unit must not be affordable
        assertTrue(a.totalCost().plus(costs.unitCost(reality, a.count())).compareTo(budget) > 0);
        // 10k owned units: unit cost is astronomically large but finite and exact
        CookieAmount unit = costs.unitCost(cursor, 10_000);
        assertTrue(unit.toPlainString().length() > 600);
        assertEquals(unit.plus(costs.unitCost(cursor, 10_001)), costs.costFor(cursor, 10_000, 2));
    }

    @Test
    void rejectsInvalidGrowth() {
        assertThrows(IllegalArgumentException.class, () -> new CostCalculator(1.0));
    }
}
