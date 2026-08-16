package de.tasticgames.lobby.cookie.domain.sim;

import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeTreeNode;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeEffect;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeEffectType;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.model.Contribution;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.NodePurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.PrestigeCheck;
import de.tasticgames.lobby.cookie.domain.model.PrestigePlan;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic "reasonable player" simulation used for balancing.
 * <p>
 * Strategy per step:
 * <ol>
 *   <li>Prestige as soon as eligible, then spend crumbs greedily on the tree.</li>
 *   <li>Score every purchasable generator/upgrade by {@code timeToAfford + cost / cpsGain}
 *       (classic idle-game ROI heuristic) and buy the best one if affordable.</li>
 *   <li>Otherwise skip time until the best candidate becomes affordable (bounded step).</li>
 * </ol>
 * Manual clicking is modelled as a steady income of
 * {@code clickValue * maxComboMultiplier * clicksPerSecond} (a sustained clicker keeps the
 * combo maxed). Golden cookies are ignored (conservative). Buffs never occur.
 */
public final class CookieSimulator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final CookieEngine engine;
    private final CookieCatalog catalog;
    private final CookieBalancing balancing;
    private final SimulationConfig config;

    public CookieSimulator(CookieEngine engine, SimulationConfig config) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.catalog = engine.catalog();
        this.balancing = engine.balancing();
        this.config = Objects.requireNonNull(config, "config");
    }

    public static CookieSimulator defaults() {
        return new CookieSimulator(CookieEngine.withDefaults(), SimulationConfig.activePlayer());
    }

    public SimulationReport run() {
        return run(CookieProfile.fresh(UUID.nameUUIDFromBytes("cookie-sim".getBytes())));
    }

    /** Runs the simulation on the given (usually fresh) profile, mutating it. */
    public SimulationReport run(CookieProfile profile) {
        long maxSeconds = (long) Math.ceil(config.maxGameHours() * 3600.0);
        long interval = config.shopCheckIntervalSeconds();
        long t = 0;
        long lastPrestigeAt = 0;
        long purchases = 0;
        long steps = 0;
        List<SimulationReport.PrestigeMilestone> milestones = new ArrayList<>();
        BigDecimal clickRate = BigDecimal.valueOf(config.clicksPerSecond())
                .multiply(BigDecimal.valueOf(balancing.comboMultiplier(balancing.maxComboStage())), MC);

        while (profile.prestigeLevel() < config.targetPrestige() && t < maxSeconds) {
            steps++;
            // 1. prestige
            PrestigeCheck check = engine.canPrestige(profile);
            if (check.eligible()) {
                PrestigePlan plan = engine.planPrestige(profile);
                engine.applyPrestige(profile, plan);
                spendCrumbs(profile);
                milestones.add(new SimulationReport.PrestigeMilestone(profile.prestigeLevel(), t, t - lastPrestigeAt));
                lastPrestigeAt = t;
                continue;
            }

            CookieStats stats = engine.compute(profile);
            double activeUntil = lastPrestigeAt + config.activeClickSeconds();
            boolean clicking = t < activeUntil;
            BigDecimal clickIncome = clicking ? stats.clickValue().multiply(clickRate, MC) : BigDecimal.ZERO;
            BigDecimal income = stats.effectiveCps().add(clickIncome);
            if (income.signum() <= 0) break; // nothing can ever be earned

            // 2. best purchase (the player only looks at the shop every `interval` seconds)
            boolean shopOpen = (t - lastPrestigeAt) % interval == 0;
            Candidate best = bestCandidate(profile, stats, income, clickIncome, clicking ? clickRate : BigDecimal.ZERO);
            if (shopOpen && best != null && profile.canAfford(best.cost)) {
                best.buy.run();
                purchases++;
                continue;
            }

            // 3. wait until affordable (or until prestige threshold if that is closer)
            BigDecimal waitFor;
            if (best != null) {
                waitFor = best.cost.toBigDecimal().subtract(profile.cookies().toBigDecimal());
            } else {
                waitFor = check.missing().toBigDecimal();
            }
            BigDecimal missingForPrestige = check.missing().toBigDecimal();
            if (missingForPrestige.signum() > 0 && missingForPrestige.compareTo(waitFor) < 0) waitFor = missingForPrestige;
            long dt = secondsToEarn(waitFor, income);
            // align purchases with the shop cadence (prestige is checked every step anyway)
            long sinceRun = t - lastPrestigeAt;
            long nextCheck = ((sinceRun + dt + interval - 1) / interval) * interval;
            dt = Math.max(1, nextCheck - sinceRun);
            dt = Math.max(1, Math.min(dt, config.maxStepSeconds()));
            if (clicking && t + dt > activeUntil) {
                // income changes when the player stops clicking: step to that boundary
                dt = Math.max(1, (long) Math.ceil(activeUntil - t));
            }
            long remaining = maxSeconds - t;
            if (dt > remaining) dt = Math.max(1, remaining);

            BigDecimal earned = income.multiply(BigDecimal.valueOf(dt), MC);
            profile.earn(CookieAmount.of(earned));
            if (clicking) profile.addClicks((long) (config.clicksPerSecond() * dt));
            profile.addPlaytimeSeconds(dt);
            t += dt;
        }
        return new SimulationReport(milestones, t, profile.prestigeLevel(),
                profile.prestigeLevel() >= config.targetPrestige(), purchases, steps);
    }

    private static long secondsToEarn(BigDecimal amount, BigDecimal incomePerSecond) {
        if (amount.signum() <= 0) return 1;
        BigDecimal seconds = amount.divide(incomePerSecond, 0, RoundingMode.CEILING);
        if (seconds.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) return Long.MAX_VALUE / 4;
        return Math.max(1, seconds.longValueExact());
    }

    // ------------------------------------------------------------------ purchase heuristic

    private record Candidate(String id, CookieAmount cost, BigDecimal score, Runnable buy) {
    }

    private Candidate bestCandidate(CookieProfile profile, CookieStats stats, BigDecimal income, BigDecimal clickIncome,
                                    BigDecimal effectiveClickRate) {
        Candidate best = null;
        BigDecimal cookies = profile.cookies().toBigDecimal();
        BigDecimal permanentGlobal = BigDecimal.valueOf(stats.prestigeMultiplier())
                .multiply(BigDecimal.valueOf(stats.upgradeCpsMultiplier()), MC)
                .multiply(BigDecimal.valueOf(stats.treeCpsMultiplier()), MC);
        Map<String, Double> generatorMults = generatorMultipliers(profile);
        Map<String, Contribution> contributions = new HashMap<>();
        for (Contribution c : stats.contributions()) contributions.put(c.generatorId(), c);

        // generators
        for (GeneratorDefinition g : catalog.generators()) {
            if (!engine.isGeneratorUnlocked(profile, g)) continue;
            int owned = profile.generatorCount(g.id());
            CookieAmount cost = engine.costs().unitCost(g, owned);
            BigDecimal gain = marginalCps(g, owned, generatorMults.getOrDefault(g.id(), 1.0), permanentGlobal);
            Candidate c = candidate(g.id(), cost, gain, cookies, income, () -> {
                PurchaseResult r = engine.buy(profile, g.id(), 1);
                if (!r.success()) throw new IllegalStateException("Simulator purchase failed: " + r);
            });
            if (c != null && (best == null || c.score.compareTo(best.score) < 0)) best = c;
        }

        // upgrades
        for (UpgradeDefinition u : engine.availableUpgrades(profile)) {
            UpgradeEffect e = u.effect();
            BigDecimal gain = switch (e.type()) {
                case GLOBAL_CPS_MULTIPLIER -> stats.effectiveCps().multiply(BigDecimal.valueOf(e.value() - 1.0), MC);
                case GENERATOR_MULTIPLIER -> {
                    Contribution c = contributions.get(e.generatorId());
                    yield c == null ? BigDecimal.ZERO : c.cpsTotal().multiply(BigDecimal.valueOf(e.value() - 1.0), MC);
                }
                case CLICK_POWER_MULTIPLIER -> clickIncome.multiply(BigDecimal.valueOf(e.value() - 1.0), MC);
                case CLICK_POWER_ADD_CPS_PERCENT -> stats.unbuffedCps()
                        .multiply(BigDecimal.valueOf(e.value() / 100.0), MC)
                        .multiply(effectiveClickRate, MC);
                default -> BigDecimal.ZERO;
            };
            Candidate c;
            if (gain.signum() > 0) {
                c = candidate(u.id(), u.cost(), gain, cookies, income, () -> buyUpgrade(profile, u.id()));
            } else if (isLuxuryAffordable(u, cookies)) {
                // luxury upgrade (golden/combo/offline): buy when cheap relative to the bank
                c = new Candidate(u.id(), u.cost(), BigDecimal.ZERO, () -> buyUpgrade(profile, u.id()));
            } else {
                c = null;
            }
            if (c != null && (best == null || c.score.compareTo(best.score) < 0)) best = c;
        }
        return best;
    }

    private boolean isLuxuryAffordable(UpgradeDefinition u, BigDecimal cookies) {
        if (u.effect().type() == UpgradeEffectType.GLOBAL_CPS_MULTIPLIER
                || u.effect().type() == UpgradeEffectType.GENERATOR_MULTIPLIER
                || u.effect().type() == UpgradeEffectType.CLICK_POWER_MULTIPLIER
                || u.effect().type() == UpgradeEffectType.CLICK_POWER_ADD_CPS_PERCENT) return false;
        return u.cost().toBigDecimal().compareTo(cookies.multiply(BigDecimal.valueOf(config.luxuryBankFraction()), MC)) <= 0;
    }

    private void buyUpgrade(CookieProfile profile, String id) {
        PurchaseResult r = engine.buyUpgrade(profile, id);
        if (!r.success()) throw new IllegalStateException("Simulator upgrade purchase failed: " + r);
    }

    private static Candidate candidate(String id, CookieAmount cost, BigDecimal gain, BigDecimal cookies,
                                       BigDecimal income, Runnable buy) {
        if (gain.signum() <= 0) return null;
        BigDecimal payback = cost.toBigDecimal().divide(gain, MC);
        BigDecimal missing = cost.toBigDecimal().subtract(cookies);
        BigDecimal timeToAfford = missing.signum() > 0 ? missing.divide(income, MC) : BigDecimal.ZERO;
        return new Candidate(id, cost, timeToAfford.add(payback), buy);
    }

    private BigDecimal marginalCps(GeneratorDefinition g, int owned, double generatorMult, BigDecimal permanentGlobal) {
        BigDecimal before = groupCps(g, owned, generatorMult);
        BigDecimal after = groupCps(g, owned + 1, generatorMult);
        return after.subtract(before).multiply(permanentGlobal, MC);
    }

    private static BigDecimal groupCps(GeneratorDefinition g, int count, double generatorMult) {
        if (count <= 0) return BigDecimal.ZERO;
        BigDecimal each = g.baseCps();
        int milestones = g.milestonesReached(count);
        if (milestones > 0) each = each.multiply(BigDecimal.valueOf(g.milestoneMultiplier()).pow(milestones, MC), MC);
        each = each.multiply(BigDecimal.valueOf(generatorMult), MC);
        return each.multiply(BigDecimal.valueOf(count));
    }

    private Map<String, Double> generatorMultipliers(CookieProfile profile) {
        Map<String, Double> map = new HashMap<>();
        for (String id : profile.upgrades()) {
            catalog.upgrade(id).ifPresent(u -> {
                if (u.effect().type() == UpgradeEffectType.GENERATOR_MULTIPLIER) {
                    map.merge(u.effect().generatorId(), u.effect().value(), (a, b) -> a * b);
                }
            });
        }
        return map;
    }

    // ------------------------------------------------------------------ crumbs

    private static final List<String> NODE_PRIORITY = List.of(
            "efficient_ovens", "iron_fingers", "head_start", "helping_hands");

    private void spendCrumbs(CookieProfile profile) {
        boolean bought = true;
        while (bought) {
            bought = false;
            PrestigeTreeNode cheapest = null;
            long cheapestCost = Long.MAX_VALUE;
            for (PrestigeTreeNode node : orderedNodes()) {
                int level = profile.prestigeUpgradeLevel(node.id());
                if (level >= node.maxLevel()) continue;
                long cost = node.costForLevel(level);
                if (cost <= profile.crumbs() && cost < cheapestCost) {
                    cheapest = node;
                    cheapestCost = cost;
                }
            }
            if (cheapest != null) {
                NodePurchaseResult r = engine.buyPrestigeNode(profile, cheapest.id());
                bought = r.success();
            }
        }
    }

    private List<PrestigeTreeNode> orderedNodes() {
        List<PrestigeTreeNode> ordered = new ArrayList<>();
        for (String id : NODE_PRIORITY) catalog.treeNode(id).ifPresent(ordered::add);
        for (PrestigeTreeNode n : catalog.prestigeTree()) if (!ordered.contains(n)) ordered.add(n);
        return ordered;
    }
}
