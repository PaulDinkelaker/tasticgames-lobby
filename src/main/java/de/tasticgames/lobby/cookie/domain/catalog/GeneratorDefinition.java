package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a passive cookie generator ("building").
 *
 * @param id                   stable snake_case id
 * @param order                display / unlock order (ascending)
 * @param baseCost             cost of the first unit in whole cookies
 * @param baseCps              cookies per second of one unit without any multipliers
 * @param unlockPrestige       minimum prestige level required to buy this generator
 * @param milestoneCounts      owned counts at which the generator's output is multiplied (sorted ascending)
 * @param milestoneMultiplier  multiplier applied per reached milestone (e.g. 2.0 = doubling)
 */
public record GeneratorDefinition(
        String id,
        int order,
        CookieAmount baseCost,
        BigDecimal baseCps,
        int unlockPrestige,
        List<Integer> milestoneCounts,
        double milestoneMultiplier
) {

    public static final List<Integer> DEFAULT_MILESTONES = List.of(10, 25, 50, 100, 200);
    public static final double DEFAULT_MILESTONE_MULTIPLIER = 2.0;

    public GeneratorDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseCost, "baseCost");
        Objects.requireNonNull(baseCps, "baseCps");
        milestoneCounts = List.copyOf(Objects.requireNonNull(milestoneCounts, "milestoneCounts"));
    }

    /** Convenience factory using default milestones (10, 25, 50, 100, 200 → x2 each). */
    public static GeneratorDefinition of(String id, int order, long baseCost, String baseCps, int unlockPrestige) {
        return new GeneratorDefinition(id, order, CookieAmount.of(baseCost), new BigDecimal(baseCps),
                unlockPrestige, DEFAULT_MILESTONES, DEFAULT_MILESTONE_MULTIPLIER);
    }

    /** Number of milestones reached with {@code owned} units. */
    public int milestonesReached(int owned) {
        int reached = 0;
        for (int m : milestoneCounts) {
            if (owned >= m) reached++;
            else break;
        }
        return reached;
    }

    /** Next milestone count strictly above {@code owned}, or -1 if none. */
    public int nextMilestone(int owned) {
        for (int m : milestoneCounts) {
            if (m > owned) return m;
        }
        return -1;
    }

    public GeneratorDefinition withBaseCps(String cps) {
        return new GeneratorDefinition(id, order, baseCost, new BigDecimal(cps), unlockPrestige, milestoneCounts, milestoneMultiplier);
    }

    public GeneratorDefinition withBaseCost(long cost) {
        return new GeneratorDefinition(id, order, CookieAmount.of(cost), baseCps, unlockPrestige, milestoneCounts, milestoneMultiplier);
    }
}
