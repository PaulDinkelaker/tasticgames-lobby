package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Generator pricing: unit price {@code ceil(baseCost * growth^owned)}, bulk prices are the exact
 * sum of per-unit prices, and {@link #maxAffordable} uses a logarithmic estimate plus loop correction.
 */
public final class CostCalculator {

    /** Precision used for the geometric growth power. Deterministic across all call sites. */
    static final MathContext MC = MathContext.DECIMAL128;

    private final BigDecimal growth;
    private final double growthDouble;

    public CostCalculator(double costGrowth) {
        if (costGrowth <= 1.0) throw new IllegalArgumentException("costGrowth must be > 1");
        this.growth = BigDecimal.valueOf(costGrowth);
        this.growthDouble = costGrowth;
    }

    public BigDecimal growth() {
        return growth;
    }

    /** Price of the next unit when {@code owned} units are already owned. */
    public CookieAmount unitCost(GeneratorDefinition generator, int owned) {
        Objects.requireNonNull(generator, "generator");
        if (owned < 0) throw new IllegalArgumentException("owned must be >= 0");
        if (owned == 0) return generator.baseCost().ceil();
        BigDecimal factor = growth.pow(owned, MC);
        return CookieAmount.of(generator.baseCost().toBigDecimal().multiply(factor, MC)).ceil();
    }

    /** Exact sum of the next {@code count} unit prices. */
    public CookieAmount costFor(GeneratorDefinition generator, int owned, int count) {
        if (count < 0) throw new IllegalArgumentException("count must be >= 0");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            total = total.add(unitCost(generator, owned + i).toBigDecimal());
        }
        return CookieAmount.of(total);
    }

    /** Result of {@link #maxAffordable}. */
    public record Affordable(int count, CookieAmount totalCost) {
        public static final Affordable NONE = new Affordable(0, CookieAmount.ZERO);
    }

    /**
     * Largest {@code n} such that {@code costFor(generator, owned, n) <= budget}, with the exact total.
     * Uses the closed-form geometric estimate as a starting point and corrects with unit steps.
     */
    public Affordable maxAffordable(GeneratorDefinition generator, int owned, CookieAmount budget) {
        Objects.requireNonNull(budget, "budget");
        CookieAmount first = unitCost(generator, owned);
        if (budget.compareTo(first) < 0) return Affordable.NONE;

        // estimate n from  base*g^owned*(g^n - 1)/(g - 1) <= budget
        double logBudget = BigMath.log10(budget.toBigDecimal());
        double logFirst = BigMath.log10(first.toBigDecimal());
        double logG = Math.log10(growthDouble);
        // g^n <= budget*(g-1)/first + 1  →  n <= log(budget*(g-1)/first + 1)/log(g)
        double ratioLog10 = logBudget + Math.log10(growthDouble - 1.0) - logFirst; // log10(budget*(g-1)/first)
        double estimate;
        if (ratioLog10 > 15) {
            estimate = ratioLog10 / logG;                     // "+1" negligible
        } else {
            estimate = Math.log10(Math.pow(10, ratioLog10) + 1.0) / logG;
        }
        int n = (int) Math.max(0, Math.min(Integer.MAX_VALUE - 1_000, Math.floor(estimate)) - 2);

        BigDecimal running = costFor(generator, owned, n).toBigDecimal();
        BigDecimal budgetValue = budget.toBigDecimal();
        // step down if the estimate overshoots (ceil rounding)
        while (n > 0 && running.compareTo(budgetValue) > 0) {
            n--;
            running = running.subtract(unitCost(generator, owned + n).toBigDecimal());
        }
        // step up while the next unit is still affordable
        while (true) {
            BigDecimal next = unitCost(generator, owned + n).toBigDecimal();
            BigDecimal candidate = running.add(next);
            if (candidate.compareTo(budgetValue) > 0) break;
            running = candidate;
            n++;
        }
        return new Affordable(n, CookieAmount.of(running));
    }
}
