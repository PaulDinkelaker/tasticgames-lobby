package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeTreeEffectType;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeTreeNode;
import de.tasticgames.lobby.cookie.domain.catalog.SpecialCookieTuning;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeEffect;
import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.model.ActiveBuff;
import de.tasticgames.lobby.cookie.domain.model.BuffType;
import de.tasticgames.lobby.cookie.domain.model.BuyMode;
import de.tasticgames.lobby.cookie.domain.model.ClickHistory;
import de.tasticgames.lobby.cookie.domain.model.ClickResult;
import de.tasticgames.lobby.cookie.domain.model.Contribution;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.NodePurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.OfflineResult;
import de.tasticgames.lobby.cookie.domain.model.PrestigeCheck;
import de.tasticgames.lobby.cookie.domain.model.PrestigePlan;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieReward;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRoll;
import de.tasticgames.lobby.cookie.domain.model.ZoneAccess;
import de.tasticgames.lobby.cookie.domain.prestige.PrestigeCalculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Pure, deterministic domain engine of the Cookie Clicker minigame. Holds no per-player state;
 * all methods operate on a {@link CookieProfile} passed in by the caller. Every mutating method
 * marks the profile dirty. Not thread-safe with respect to a single profile; the plugin serializes
 * access per player.
 */
public final class CookieEngine {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Lowest special cookie chance multiplier used for scheduling; keeps a pathological config from stalling the timer. */
    private static final double MIN_CHANCE_MULTIPLIER = 0.01;

    private final CookieCatalog catalog;
    private final CookieBalancing balancing;
    private final CostCalculator costs;
    private final AchievementEvaluator achievementEvaluator;

    public CookieEngine(CookieCatalog catalog, CookieBalancing balancing) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.balancing = Objects.requireNonNull(balancing, "balancing");
        this.costs = new CostCalculator(balancing.costGrowth());
        this.achievementEvaluator = new AchievementEvaluator(catalog);
    }

    public static CookieEngine withDefaults() {
        return new CookieEngine(CookieCatalog.defaults(), CookieBalancing.defaults());
    }

    public CookieCatalog catalog() { return catalog; }

    public CookieBalancing balancing() { return balancing; }

    public CostCalculator costs() { return costs; }

    public AchievementEvaluator achievementEvaluator() { return achievementEvaluator; }

    // =====================================================================================
    // Stats
    // =====================================================================================

    /** Computes all derived numbers of the profile using the profile's currently active buffs. */
    public CookieStats compute(CookieProfile profile) {
        Objects.requireNonNull(profile, "profile");

        PrestigeDefinition prestige = catalog.prestige(profile.prestigeLevel())
                .orElseGet(() -> catalog.requirePrestige(catalog.maxPrestigeLevel()));
        double prestigeMult = prestige.totalMultiplier();

        // ---- upgrade effects
        double upgradeCpsMult = 1.0;
        double upgradeClickMult = 1.0;
        double clickCpsPercent = 0.0;
        double goldenFreqMult = 1.0;
        double goldenValueMult = 1.0;
        double comboDurationMult = 1.0;
        double offlineAdd = 0.0;
        Map<String, Double> generatorMults = new HashMap<>();
        Map<SpecialCookieRarity, Double> rarityMults = new EnumMap<>(SpecialCookieRarity.class);
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) rarityMults.put(rarity, 1.0);
        for (String upgradeId : profile.upgrades()) {
            Optional<UpgradeDefinition> def = catalog.upgrade(upgradeId);
            if (def.isEmpty()) continue; // unknown / removed upgrade: ignore gracefully
            UpgradeEffect e = def.get().effect();
            switch (e.type()) {
                case CLICK_POWER_MULTIPLIER -> upgradeClickMult *= e.value();
                case CLICK_POWER_ADD_CPS_PERCENT -> clickCpsPercent += e.value();
                case GLOBAL_CPS_MULTIPLIER -> upgradeCpsMult *= e.value();
                case GENERATOR_MULTIPLIER -> generatorMults.merge(e.generatorId(), e.value(), (a, b) -> a * b);
                case GOLDEN_COOKIE_FREQUENCY -> goldenFreqMult *= e.value();
                case GOLDEN_COOKIE_VALUE -> goldenValueMult *= e.value();
                case SPECIAL_RARITY_WEIGHT -> rarityMults.merge(e.rarity(), e.value(), (a, b) -> a * b);
                case COMBO_DURATION -> comboDurationMult *= e.value();
                case OFFLINE_EFFICIENCY -> offlineAdd += e.value();
            }
        }

        // ---- prestige tree effects
        double treeClickPct = treeTotal(profile, PrestigeTreeEffectType.CLICK_POWER_PERCENT);
        double treeCpsPct = treeTotal(profile, PrestigeTreeEffectType.GLOBAL_CPS_PERCENT);
        double treeOfflinePct = treeTotal(profile, PrestigeTreeEffectType.OFFLINE_EFFICIENCY_PERCENT);
        double treeGoldenChancePct = treeTotal(profile, PrestigeTreeEffectType.GOLDEN_CHANCE_PERCENT);
        double treeGoldenDurationPct = treeTotal(profile, PrestigeTreeEffectType.GOLDEN_DURATION_PERCENT);
        double treeComboPct = treeTotal(profile, PrestigeTreeEffectType.COMBO_DURATION_PERCENT);
        double treeRarityLuckPct = treeTotal(profile, PrestigeTreeEffectType.RARITY_LUCK_PERCENT);
        double treeClickMult = 1.0 + treeClickPct / 100.0;
        double treeCpsMult = 1.0 + treeCpsPct / 100.0;
        if (treeRarityLuckPct != 0.0) {
            // the connoisseur node only improves the rare half of the table (platinum and above)
            double rarityLuckMult = 1.0 + treeRarityLuckPct / 100.0;
            for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
                if (rarity.atLeast(SpecialCookieRarity.RARE_FROM)) rarityMults.merge(rarity, rarityLuckMult, (a, b) -> a * b);
            }
        }

        // ---- buffs
        double buffCpsMult = 1.0;
        double buffClickMult = 1.0;
        for (ActiveBuff buff : profile.activeBuffs()) {
            if (buff.type().affectsCps()) buffCpsMult *= buff.multiplier();
            if (buff.type().affectsClicks()) buffClickMult *= buff.multiplier();
        }

        // ---- generators
        BigDecimal permanentGlobal = BigDecimal.valueOf(prestigeMult)
                .multiply(BigDecimal.valueOf(upgradeCpsMult), MC)
                .multiply(BigDecimal.valueOf(treeCpsMult), MC);
        BigDecimal totalGlobal = permanentGlobal.multiply(BigDecimal.valueOf(buffCpsMult), MC);

        BigDecimal baseCps = BigDecimal.ZERO;
        List<Contribution> contributions = new ArrayList<>();
        for (GeneratorDefinition g : catalog.generators()) {
            int count = profile.generatorCount(g.id());
            if (count <= 0) continue;
            BigDecimal each = g.baseCps();
            int milestones = g.milestonesReached(count);
            if (milestones > 0) {
                each = each.multiply(BigDecimal.valueOf(g.milestoneMultiplier()).pow(milestones, MC), MC);
            }
            Double gm = generatorMults.get(g.id());
            if (gm != null) each = each.multiply(BigDecimal.valueOf(gm), MC);
            BigDecimal groupBase = each.multiply(BigDecimal.valueOf(count));
            baseCps = baseCps.add(groupBase);
            BigDecimal effectiveEach = each.multiply(totalGlobal, MC);
            contributions.add(new Contribution(g.id(), count, effectiveEach, effectiveEach.multiply(BigDecimal.valueOf(count))));
        }
        BigDecimal unbuffedCps = baseCps.multiply(permanentGlobal, MC);
        BigDecimal effectiveCps = baseCps.multiply(totalGlobal, MC);

        // ---- click value
        BigDecimal cpsShare = unbuffedCps.multiply(BigDecimal.valueOf(clickCpsPercent), MC).divide(HUNDRED, MC);
        BigDecimal baseClick = BigDecimal.ONE
                .multiply(BigDecimal.valueOf(upgradeClickMult), MC)
                .multiply(BigDecimal.valueOf(treeClickMult), MC)
                .multiply(BigDecimal.valueOf(prestigeMult), MC)
                .add(cpsShare);
        BigDecimal clickValue = baseClick.multiply(BigDecimal.valueOf(buffClickMult), MC);

        // ---- misc
        double offlineEfficiency = Math.min(1.0, Math.max(0.0, balancing.offlineEfficiency() + offlineAdd + treeOfflinePct / 100.0));
        double goldenChanceMult = goldenFreqMult * (1.0 + treeGoldenChancePct / 100.0);
        double goldenDurationMult = 1.0 + treeGoldenDurationPct / 100.0;
        double comboMult = comboDurationMult * (1.0 + treeComboPct / 100.0);

        return new CookieStats(clickValue, baseClick, baseCps, unbuffedCps, effectiveCps, contributions,
                prestigeMult, upgradeCpsMult, treeCpsMult, upgradeClickMult, treeClickMult, clickCpsPercent,
                buffCpsMult, buffClickMult, offlineEfficiency, goldenChanceMult, goldenValueMult,
                goldenDurationMult, comboMult, rarityMults);
    }

    private double treeTotal(CookieProfile profile, PrestigeTreeEffectType type) {
        double total = 0.0;
        for (PrestigeTreeNode node : catalog.prestigeTree()) {
            if (node.effect() != type) continue;
            int level = profile.prestigeUpgradeLevel(node.id());
            if (level > 0) total += node.totalValue(level);
        }
        return total;
    }

    // =====================================================================================
    // Buffs
    // =====================================================================================

    /** Removes expired buffs. Returns the number removed. Buffs are transient, so this does not dirty the profile. */
    public int expireBuffs(CookieProfile profile, Instant now) {
        int removed = 0;
        Iterator<ActiveBuff> it = profile.activeBuffs().iterator();
        while (it.hasNext()) {
            if (it.next().isExpiredAt(now)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Adds a buff (transient). */
    public void addBuff(CookieProfile profile, ActiveBuff buff) {
        profile.addBuff(buff);
    }

    // =====================================================================================
    // Clicking
    // =====================================================================================

    /**
     * Processes one click at {@code now}. Enforces the server-side click-rate cap (excess clicks are
     * counted but earn nothing), advances/decays the combo and credits the reward.
     */
    public ClickResult click(CookieProfile profile, Instant now) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(now, "now");
        expireBuffs(profile, now);
        long nowMs = now.toEpochMilli();

        // ---- rate limit
        ClickHistory history = profile.clickHistory();
        history.record(nowMs);
        int clicksInLastSecond = history.countAfter(nowMs - 1000L);
        boolean rateLimited = clicksInLastSecond > balancing.maxClicksPerSecond();

        // ---- combo
        CookieStats stats = compute(profile);
        long decayMs = (long) Math.ceil(balancing.comboDecayMillis() * stats.comboDurationMultiplier());
        long last = profile.lastClickEpochMillis();
        int stage = profile.comboStage();
        int rapid = profile.comboRapidClicks();
        boolean advanced = false;
        if (last == Long.MIN_VALUE || nowMs - last > decayMs) {
            stage = 0;
            rapid = 1;
        } else if (nowMs - last <= balancing.comboWindowMillis()) {
            rapid++;
            if (rapid >= balancing.clicksPerComboStage() && stage < balancing.maxComboStage()) {
                stage++;
                rapid = 0;
                advanced = true;
            } else if (rapid >= balancing.clicksPerComboStage()) {
                rapid = 0;
            }
        } else {
            rapid = 1; // gap too large for a rapid streak, combo stage kept until decay
        }
        profile.setComboState(stage, rapid, nowMs);
        profile.recordCombo(stage);
        double comboMultiplier = balancing.comboMultiplier(stage);

        // ---- reward
        profile.incrementClicks();
        CookieAmount reward = CookieAmount.ZERO;
        if (!rateLimited) {
            BigDecimal payout = stats.clickValue().multiply(BigDecimal.valueOf(comboMultiplier), MC);
            BigDecimal cap = specialClickCap(profile, stats, now, comboMultiplier);
            if (cap != null && payout.compareTo(cap) > 0) {
                payout = cap;
            }
            reward = CookieAmount.of(payout);
            profile.earn(reward);
        }
        profile.markDirty();
        return new ClickResult(reward, stage, comboMultiplier, rateLimited, advanced, profile.cookies());
    }

    /**
     * Ceiling for a single click while a special cookie's click buff is active: a click may never pay more than
     * {@code balancing.specialClickClampCpsSeconds()} seconds of unbuffed production (and never less than the
     * unbuffed click itself). Without it a MASTER click frenzy plus an autoclicker outperforms every other reward
     * by orders of magnitude. Returns {@code null} when no clamp applies.
     */
    private BigDecimal specialClickCap(CookieProfile profile, CookieStats stats, Instant now, double comboMultiplier) {
        long seconds = balancing.specialClickClampCpsSeconds();
        if (seconds <= 0 || stats.unbuffedCps().signum() <= 0) {
            return null;
        }
        boolean specialClickBuff = false;
        for (ActiveBuff buff : profile.activeBuffs()) {
            if (buff.isActiveAt(now) && buff.type().affectsClicks() && buff.source().startsWith(SPECIAL_BUFF_SOURCE)) {
                specialClickBuff = true;
                break;
            }
        }
        if (!specialClickBuff) {
            return null;
        }
        BigDecimal cap = stats.unbuffedCps().multiply(BigDecimal.valueOf(seconds), MC);
        BigDecimal unbuffedClick = stats.baseClickValue().multiply(BigDecimal.valueOf(comboMultiplier), MC);
        return cap.max(unbuffedClick);
    }

    /** Resets the combo if the player has been idle longer than the (multiplied) decay time. */
    public boolean decayCombo(CookieProfile profile, Instant now) {
        long last = profile.lastClickEpochMillis();
        if (last == Long.MIN_VALUE || profile.comboStage() == 0) return false;
        CookieStats stats = compute(profile);
        long decayMs = (long) Math.ceil(balancing.comboDecayMillis() * stats.comboDurationMultiplier());
        if (now.toEpochMilli() - last > decayMs) {
            profile.setComboState(0, 0, last);
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Passive production
    // =====================================================================================

    /** Credits {@code effectiveCps * elapsedSeconds}. Returns the produced amount. */
    public CookieAmount produce(CookieProfile profile, Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative() || elapsed.isZero()) return CookieAmount.ZERO;
        CookieStats stats = compute(profile);
        if (stats.effectiveCps().signum() <= 0) return CookieAmount.ZERO;
        BigDecimal seconds = BigMath.secondsOf(elapsed);
        CookieAmount produced = CookieAmount.of(stats.effectiveCps().multiply(seconds, MC));
        profile.earn(produced);
        return produced;
    }

    /** Convenience: production for a tick spanning {@code (previousTick, now]}. */
    public CookieAmount produce(CookieProfile profile, Instant previousTick, Instant now) {
        return produce(profile, Duration.between(previousTick, now));
    }

    // =====================================================================================
    // Offline
    // =====================================================================================

    /** Computes offline production without mutating the profile. */
    public OfflineResult previewOffline(CookieProfile profile, Instant lastActive, Instant now) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(now, "now");
        if (!balancing.offlineEnabled() || lastActive == null) return OfflineResult.NONE;
        Instant claimedUntil = profile.offlineClaimedUntil();
        if (claimedUntil != null && !now.isAfter(claimedUntil)) return OfflineResult.NONE;
        Instant from = lastActive;
        if (claimedUntil != null && claimedUntil.isAfter(from)) from = claimedUntil;
        if (!now.isAfter(from)) return OfflineResult.NONE;

        long seconds = Duration.between(from, now).getSeconds();
        long capped = Math.min(seconds, balancing.offlineMaxSeconds());
        if (capped <= 0) return new OfflineResult(seconds, 0, 0, CookieAmount.ZERO);

        CookieStats stats = compute(profile);
        double efficiency = stats.offlineEfficiency();
        BigDecimal cookies = stats.unbuffedCps()
                .multiply(BigDecimal.valueOf(capped))
                .multiply(BigDecimal.valueOf(efficiency), MC);
        return new OfflineResult(seconds, capped, efficiency, CookieAmount.of(cookies));
    }

    /**
     * Credits offline production for {@code (lastActive, now]} and marks the window as claimed.
     * Returns zero when disabled or when {@code now <= offlineClaimedUntil}.
     */
    public OfflineResult offlineProduction(CookieProfile profile, Instant lastActive, Instant now) {
        OfflineResult result = previewOffline(profile, lastActive, now);
        if (result == OfflineResult.NONE) return result;
        if (!result.cookies().isZero()) profile.earn(result.cookies());
        profile.setOfflineClaimedUntil(now);
        profile.setLastActiveAt(now);
        profile.markDirty();
        return result;
    }

    /** Offline production based on the profile's own {@code lastActiveAt}. */
    public OfflineResult offlineProduction(CookieProfile profile, Instant now) {
        return offlineProduction(profile, profile.lastActiveAt(), now);
    }

    // =====================================================================================
    // Purchases
    // =====================================================================================

    public CookieAmount unitCost(String generatorId, int owned) {
        return costs.unitCost(catalog.requireGenerator(generatorId), owned);
    }

    public CookieAmount costFor(GeneratorDefinition generator, int owned, int count) {
        return costs.costFor(generator, owned, count);
    }

    public CookieAmount costFor(String generatorId, int owned, int count) {
        return costs.costFor(catalog.requireGenerator(generatorId), owned, count);
    }

    public CostCalculator.Affordable maxAffordable(GeneratorDefinition generator, int owned, CookieAmount cookies) {
        return costs.maxAffordable(generator, owned, cookies);
    }

    /** Whether the generator is unlocked for the profile's prestige level. */
    public boolean isGeneratorUnlocked(CookieProfile profile, GeneratorDefinition generator) {
        return generator.unlockPrestige() <= profile.prestigeLevel();
    }

    /** Buys generators according to the mode. */
    public PurchaseResult buy(CookieProfile profile, String generatorId, BuyMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode.isMax()) return buyMax(profile, generatorId);
        return buy(profile, generatorId, mode.fixedCount());
    }

    /** Buys exactly {@code count} generators or nothing. */
    public PurchaseResult buy(CookieProfile profile, String generatorId, int count) {
        Objects.requireNonNull(profile, "profile");
        Optional<GeneratorDefinition> def = catalog.generator(generatorId);
        int owned = profile.generatorCount(generatorId);
        if (def.isEmpty()) return PurchaseResult.failure(PurchaseResult.Reason.UNKNOWN_ITEM, generatorId, owned, profile.cookies());
        GeneratorDefinition g = def.get();
        if (!isGeneratorUnlocked(profile, g)) return PurchaseResult.failure(PurchaseResult.Reason.LOCKED, generatorId, owned, profile.cookies());
        if (count <= 0) return PurchaseResult.failure(PurchaseResult.Reason.INVALID_COUNT, generatorId, owned, profile.cookies());
        CookieAmount total = costs.costFor(g, owned, count);
        if (!profile.canAfford(total)) return PurchaseResult.failure(PurchaseResult.Reason.INSUFFICIENT_FUNDS, generatorId, owned, profile.cookies());
        profile.spend(total);
        profile.addGenerators(generatorId, count);
        profile.markDirty();
        return PurchaseResult.success(generatorId, count, total, owned + count, profile.cookies());
    }

    /** Buys as many generators as affordable (at least one). */
    public PurchaseResult buyMax(CookieProfile profile, String generatorId) {
        Objects.requireNonNull(profile, "profile");
        Optional<GeneratorDefinition> def = catalog.generator(generatorId);
        int owned = profile.generatorCount(generatorId);
        if (def.isEmpty()) return PurchaseResult.failure(PurchaseResult.Reason.UNKNOWN_ITEM, generatorId, owned, profile.cookies());
        GeneratorDefinition g = def.get();
        if (!isGeneratorUnlocked(profile, g)) return PurchaseResult.failure(PurchaseResult.Reason.LOCKED, generatorId, owned, profile.cookies());
        CostCalculator.Affordable affordable = costs.maxAffordable(g, owned, profile.cookies());
        if (affordable.count() <= 0) return PurchaseResult.failure(PurchaseResult.Reason.INSUFFICIENT_FUNDS, generatorId, owned, profile.cookies());
        profile.spend(affordable.totalCost());
        profile.addGenerators(generatorId, affordable.count());
        profile.markDirty();
        return PurchaseResult.success(generatorId, affordable.count(), affordable.totalCost(), owned + affordable.count(), profile.cookies());
    }

    /** Whether the upgrade is visible/purchasable (ignoring cost) for the profile. */
    public boolean isUpgradeAvailable(CookieProfile profile, UpgradeDefinition upgrade) {
        if (profile.hasUpgrade(upgrade.id())) return false;
        if (upgrade.unlockPrestige() > profile.prestigeLevel()) return false;
        if (upgrade.hasGeneratorRequirement()
                && profile.generatorCount(upgrade.requiredGeneratorId()) < upgrade.requiredCount()) return false;
        return true;
    }

    /** All upgrades the profile could buy now ignoring cost. */
    public List<UpgradeDefinition> availableUpgrades(CookieProfile profile) {
        List<UpgradeDefinition> list = new ArrayList<>();
        for (UpgradeDefinition u : catalog.upgrades()) {
            if (isUpgradeAvailable(profile, u)) list.add(u);
        }
        return list;
    }

    public PurchaseResult buyUpgrade(CookieProfile profile, String upgradeId) {
        Objects.requireNonNull(profile, "profile");
        Optional<UpgradeDefinition> def = catalog.upgrade(upgradeId);
        if (def.isEmpty()) return PurchaseResult.failure(PurchaseResult.Reason.UNKNOWN_ITEM, upgradeId, 0, profile.cookies());
        UpgradeDefinition u = def.get();
        if (profile.hasUpgrade(upgradeId)) return PurchaseResult.failure(PurchaseResult.Reason.ALREADY_OWNED, upgradeId, 1, profile.cookies());
        if (u.unlockPrestige() > profile.prestigeLevel()) return PurchaseResult.failure(PurchaseResult.Reason.LOCKED, upgradeId, 0, profile.cookies());
        if (u.hasGeneratorRequirement() && profile.generatorCount(u.requiredGeneratorId()) < u.requiredCount()) {
            return PurchaseResult.failure(PurchaseResult.Reason.REQUIREMENT_NOT_MET, upgradeId, 0, profile.cookies());
        }
        if (!profile.canAfford(u.cost())) return PurchaseResult.failure(PurchaseResult.Reason.INSUFFICIENT_FUNDS, upgradeId, 0, profile.cookies());
        profile.spend(u.cost());
        profile.addUpgrade(upgradeId);
        profile.markDirty();
        return PurchaseResult.success(upgradeId, 1, u.cost(), 1, profile.cookies());
    }

    // =====================================================================================
    // Prestige
    // =====================================================================================

    public PrestigeCheck canPrestige(CookieProfile profile) {
        int current = profile.prestigeLevel();
        int next = current + 1;
        Optional<PrestigeDefinition> nextDef = catalog.prestige(next);
        CookieAmount lifetime = profile.lifetimeCookies();
        if (nextDef.isEmpty()) {
            return new PrestigeCheck(false, current, -1, CookieAmount.ZERO, lifetime, CookieAmount.ZERO, true);
        }
        CookieAmount required = CookieAmount.of(nextDef.get().requiredLifetimeCookies());
        boolean eligible = lifetime.compareTo(required) >= 0;
        CookieAmount missing = eligible ? CookieAmount.ZERO : required.minus(lifetime);
        return new PrestigeCheck(eligible, current, next, required, lifetime, missing, false);
    }

    /** Describes the prestige without mutating anything. */
    public PrestigePlan planPrestige(CookieProfile profile) {
        PrestigeCheck check = canPrestige(profile);
        int from = profile.prestigeLevel();
        if (!check.eligible()) {
            return new PrestigePlan(false, from, from, 0, profile.crumbs(), catalog.requirePrestige(from).totalMultiplier(),
                    List.of(), null, List.of(), profile.cookies(), profile.generators(), PrestigePlan.KEEPS, PrestigePlan.RESETS);
        }
        PrestigeDefinition target = catalog.requirePrestige(check.nextLevel());
        long gained = PrestigeCalculator.crumbsGained(profile.lifetimeCookies(), profile.crumbsEarnedTotal());
        long crumbsAfter = Math.addExact(profile.crumbs(), gained);
        return new PrestigePlan(true, from, target.level(), gained, crumbsAfter, target.totalMultiplier(),
                target.rewardCosmeticIds(), target.unlockedZoneId(), target.unlockedGeneratorIds(),
                startingCookies(profile), startingGenerators(profile), PrestigePlan.KEEPS, PrestigePlan.RESETS);
    }

    /** Applies a previously planned prestige. */
    public PrestigePlan applyPrestige(CookieProfile profile, PrestigePlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.eligible()) throw new IllegalStateException("Prestige plan is not eligible");
        if (plan.fromLevel() != profile.prestigeLevel()) {
            throw new IllegalStateException("Stale prestige plan: profile is at level " + profile.prestigeLevel()
                    + " but plan starts at " + plan.fromLevel());
        }
        PrestigeCheck check = canPrestige(profile);
        if (!check.eligible() || check.nextLevel() != plan.toLevel()) {
            throw new IllegalStateException("Profile is no longer eligible for prestige to level " + plan.toLevel());
        }
        long gained = PrestigeCalculator.crumbsGained(profile.lifetimeCookies(), profile.crumbsEarnedTotal());
        profile.addCrumbs(gained);
        profile.setPrestigeLevel(plan.toLevel());
        profile.setCookies(startingCookies(profile));
        profile.setGenerators(startingGenerators(profile));
        profile.clearUpgrades();
        profile.clearBuffs();
        profile.resetCombo();
        profile.markDirty();
        return new PrestigePlan(true, plan.fromLevel(), plan.toLevel(), gained, profile.crumbs(), plan.newMultiplier(),
                plan.rewardCosmeticIds(), plan.unlockedZoneId(), plan.unlockedGeneratorIds(), profile.cookies(),
                profile.generators(), plan.keeps(), plan.resets());
    }

    /** Plans and applies in one step; returns the applied plan or an ineligible plan. */
    public PrestigePlan prestige(CookieProfile profile) {
        PrestigePlan plan = planPrestige(profile);
        if (!plan.eligible()) return plan;
        return applyPrestige(profile, plan);
    }

    /** Starting bank of a new run according to the prestige tree. */
    public CookieAmount startingCookies(CookieProfile profile) {
        BigDecimal total = BigDecimal.ZERO;
        for (PrestigeTreeNode node : catalog.prestigeTree()) {
            if (node.effect() != PrestigeTreeEffectType.STARTING_COOKIES) continue;
            int level = profile.prestigeUpgradeLevel(node.id());
            if (level > 0) total = total.add(BigDecimal.valueOf(node.totalValue(level)));
        }
        return CookieAmount.of(total);
    }

    /** Starting generators of a new run according to the prestige tree. */
    public Map<String, Integer> startingGenerators(CookieProfile profile) {
        Map<String, Integer> map = new HashMap<>();
        for (PrestigeTreeNode node : catalog.prestigeTree()) {
            if (node.effect() != PrestigeTreeEffectType.STARTING_GENERATORS) continue;
            int level = profile.prestigeUpgradeLevel(node.id());
            if (level > 0) {
                int units = (int) Math.round(node.totalValue(level));
                if (units > 0) map.merge(node.targetGeneratorId(), units, Integer::sum);
            }
        }
        return map;
    }

    // =====================================================================================
    // Prestige tree
    // =====================================================================================

    public long nodeCost(CookieProfile profile, PrestigeTreeNode node) {
        return node.costForLevel(profile.prestigeUpgradeLevel(node.id()));
    }

    public NodePurchaseResult buyPrestigeNode(CookieProfile profile, String nodeId) {
        Objects.requireNonNull(profile, "profile");
        Optional<PrestigeTreeNode> def = catalog.treeNode(nodeId);
        int level = profile.prestigeUpgradeLevel(nodeId);
        if (def.isEmpty()) return NodePurchaseResult.failure(NodePurchaseResult.Reason.UNKNOWN_NODE, nodeId, level, profile.crumbs());
        PrestigeTreeNode node = def.get();
        if (level >= node.maxLevel()) return NodePurchaseResult.failure(NodePurchaseResult.Reason.MAX_LEVEL, nodeId, level, profile.crumbs());
        long cost = node.costForLevel(level);
        if (cost > profile.crumbs()) return NodePurchaseResult.failure(NodePurchaseResult.Reason.INSUFFICIENT_CRUMBS, nodeId, level, profile.crumbs());
        profile.spendCrumbs(cost);
        profile.setPrestigeUpgradeLevel(nodeId, level + 1);
        profile.markDirty();
        return new NodePurchaseResult(true, NodePurchaseResult.Reason.OK, nodeId, level + 1, cost, profile.crumbs());
    }

    // =====================================================================================
    // Zones
    // =====================================================================================

    public ZoneAccess canEnter(CookieProfile profile, String zoneId) {
        Optional<ZoneDefinition> zone = catalog.zone(zoneId);
        int current = profile.prestigeLevel();
        if (zone.isEmpty()) return new ZoneAccess(false, zoneId, -1, current, ZoneAccess.Reason.UNKNOWN_ZONE);
        int required = zone.get().minPrestige();
        if (current < required) return new ZoneAccess(false, zoneId, required, current, ZoneAccess.Reason.PRESTIGE_TOO_LOW);
        return new ZoneAccess(true, zoneId, required, current, ZoneAccess.Reason.OK);
    }

    /** Marks a zone as discovered if accessible. Returns true only on first discovery. */
    public boolean discover(CookieProfile profile, String zoneId) {
        if (!canEnter(profile, zoneId).allowed()) return false;
        boolean added = profile.addDiscoveredZone(zoneId);
        if (added) profile.markDirty();
        return added;
    }

    // =====================================================================================
    // Special cookies
    // =====================================================================================

    /** Rarities the profile has unlocked, in ascending rarity (empty below prestige 1). */
    public List<SpecialCookieRarity> unlockedRarities(CookieProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return SpecialCookieRarity.unlockedFor(profile.prestigeLevel());
    }

    /**
     * Effective, not yet normalised draw weights of the unlocked rarities
     * ({@code balancing weight × upgrades × prestige tree}); rarities weighted to zero are omitted.
     */
    public Map<SpecialCookieRarity, Double> rarityWeights(CookieProfile profile) {
        CookieStats stats = compute(profile);
        Map<SpecialCookieRarity, Double> weights = new EnumMap<>(SpecialCookieRarity.class);
        for (SpecialCookieRarity rarity : unlockedRarities(profile)) {
            double weight = balancing.tuning(rarity).weight() * stats.specialWeightMultiplier(rarity);
            if (weight > 0) weights.put(rarity, weight);
        }
        return weights;
    }

    /** Draw chances of the unlocked rarities, normalised to 1 (empty when the profile draws nothing). */
    public Map<SpecialCookieRarity, Double> rarityChances(CookieProfile profile) {
        Map<SpecialCookieRarity, Double> weights = rarityWeights(profile);
        double total = 0;
        for (double weight : weights.values()) total += weight;
        if (total <= 0) return Map.of();
        Map<SpecialCookieRarity, Double> chances = new EnumMap<>(SpecialCookieRarity.class);
        for (Map.Entry<SpecialCookieRarity, Double> entry : weights.entrySet()) {
            chances.put(entry.getKey(), entry.getValue() / total);
        }
        return chances;
    }

    /** Draws one rarity by weight; empty when the profile has none unlocked (prestige 0). */
    public Optional<SpecialCookieRarity> rollRarity(CookieProfile profile, RandomGenerator rng) {
        Objects.requireNonNull(rng, "rng");
        Map<SpecialCookieRarity, Double> weights = rarityWeights(profile);
        double total = 0;
        for (double weight : weights.values()) total += weight;
        if (total <= 0) return Optional.empty();
        double roll = rng.nextDouble() * total;
        SpecialCookieRarity last = null;
        for (Map.Entry<SpecialCookieRarity, Double> entry : weights.entrySet()) {
            last = entry.getKey();
            roll -= entry.getValue();
            if (roll < 0) return Optional.of(last);
        }
        return Optional.ofNullable(last); // rounding fallback: the highest unlocked rarity
    }

    /**
     * Draws the instant of the profile's next special cookie:
     * {@code now + uniform(minInterval, maxInterval) / chanceMultiplier}, never sooner than the
     * configured floor. The timer is per session and always redrawn from a full interval, so
     * reconnecting can only lengthen the wait, never shorten it below the floor.
     */
    public Instant scheduleNextSpecial(CookieProfile profile, RandomGenerator rng, Instant now) {
        Objects.requireNonNull(rng, "rng");
        Objects.requireNonNull(now, "now");
        double chance = Math.max(MIN_CHANCE_MULTIPLIER, compute(profile).goldenChanceMultiplier());
        double min = balancing.specialMinIntervalSeconds();
        double max = balancing.specialMaxIntervalSeconds();
        double drawn = min + rng.nextDouble() * (max - min);
        double seconds = Math.max(balancing.specialFloorIntervalSeconds(), drawn / chance);
        return now.plusMillis(Math.round(seconds * 1000.0));
    }

    /**
     * Draws the rarity of a special cookie that just became due, together with the window a
     * cookie spawned in the world stays clickable. Empty below prestige 1.
     */
    public Optional<SpecialCookieRoll> rollSpecial(CookieProfile profile, RandomGenerator rng, Instant now) {
        Objects.requireNonNull(now, "now");
        return rollRarity(profile, rng)
                .map(rarity -> new SpecialCookieRoll(rarity, now, now.plusSeconds(balancing.specialLifetimeSeconds())));
    }

    /** Picks a reward of the rarity without applying it. */
    public SpecialCookieReward rewardFor(CookieProfile profile, SpecialCookieRarity rarity, RandomGenerator rng, Instant now) {
        return rewardFor(profile, rarity, rng, now, true);
    }

    /**
     * Rolls a reward for the rarity. {@code clickableCookie} tells the engine whether the player can actually
     * click a cookie right now (the main cookie stands in the lobby; the open world has none) – click-only
     * rewards are dropped from the draw when they would be worth nothing, and the remaining weights renormalise.
     */
    public SpecialCookieReward rewardFor(CookieProfile profile, SpecialCookieRarity rarity, RandomGenerator rng,
                                         Instant now, boolean clickableCookie) {
        Objects.requireNonNull(rng, "rng");
        Map<GoldenRewardType, Integer> weights = balancing.tuning(rarity).rewardWeights();
        if (!clickableCookie) {
            Map<GoldenRewardType, Integer> reachable = new EnumMap<>(GoldenRewardType.class);
            for (Map.Entry<GoldenRewardType, Integer> entry : weights.entrySet()) {
                if (entry.getKey() != GoldenRewardType.CLICK_FRENZY && entry.getValue() > 0) {
                    reachable.put(entry.getKey(), entry.getValue());
                }
            }
            if (!reachable.isEmpty()) {
                weights = reachable;
            }
        }
        return rewardOfType(profile, rarity, pickRewardType(weights, rng), now);
    }

    /** Prefix of every buff a special cookie hands out ({@code special:<RARITY>:<TYPE>}). */
    static final String SPECIAL_BUFF_SOURCE = "special:";

    /** Builds the reward of a specific rarity and type (deterministic). */
    public SpecialCookieReward rewardOfType(CookieProfile profile, SpecialCookieRarity rarity, GoldenRewardType type, Instant now) {
        Objects.requireNonNull(type, "type");
        SpecialCookieTuning tuning = balancing.tuning(rarity);
        CookieStats stats = compute(profile);
        BigDecimal valueMult = BigDecimal.valueOf(stats.goldenValueMultiplier());
        BigDecimal bank = profile.cookies().toBigDecimal();
        switch (type) {
            case LUCKY -> {
                BigDecimal bankShare = bank.multiply(BigDecimal.valueOf(tuning.luckyBankFraction()), MC);
                BigDecimal cpsShare = stats.unbuffedCps().multiply(BigDecimal.valueOf(tuning.luckyCpsSeconds()));
                BigDecimal amount = bankShare.min(cpsShare).add(BigDecimal.valueOf(tuning.luckyFlatBonus()))
                        .multiply(valueMult, MC);
                return SpecialCookieReward.instant(rarity, type, CookieAmount.of(amount));
            }
            case CHAIN_BONUS -> {
                BigDecimal bankShare = bank.multiply(BigDecimal.valueOf(tuning.chainBankFraction()), MC);
                if (tuning.chainCpsSeconds() > 0 && stats.unbuffedCps().signum() > 0) {
                    // same two-term shape as LUCKY: hoarding must not turn a chain bonus into hours of production
                    bankShare = bankShare.min(stats.unbuffedCps().multiply(BigDecimal.valueOf(tuning.chainCpsSeconds()), MC));
                }
                BigDecimal amount = bankShare.multiply(valueMult, MC);
                return SpecialCookieReward.instant(rarity, type, CookieAmount.of(amount));
            }
            case FRENZY -> {
                return SpecialCookieReward.buffed(rarity, type, buff(BuffType.FRENZY, tuning.frenzyMultiplier(),
                        tuning.frenzySeconds(), stats, now, rarity, type));
            }
            case CLICK_FRENZY -> {
                return SpecialCookieReward.buffed(rarity, type, buff(BuffType.CLICK_FRENZY, tuning.clickFrenzyMultiplier(),
                        tuning.clickFrenzySeconds(), stats, now, rarity, type));
            }
            case BLESSING -> {
                // two buffs so the stats maths stays untouched: one CPS-only, one click-only
                return SpecialCookieReward.buffed(rarity, type,
                        buff(BuffType.CPS_MULTIPLIER, tuning.blessingCpsMultiplier(), tuning.blessingSeconds(), stats, now, rarity, type),
                        buff(BuffType.CLICK_MULTIPLIER, tuning.blessingClickMultiplier(), tuning.blessingSeconds(), stats, now, rarity, type));
            }
            default -> throw new IllegalArgumentException("Unhandled reward type " + type);
        }
    }

    private static ActiveBuff buff(BuffType buffType, double multiplier, int seconds, CookieStats stats, Instant now,
                                   SpecialCookieRarity rarity, GoldenRewardType type) {
        long millis = (long) Math.ceil(seconds * 1000.0 * stats.goldenDurationMultiplier());
        return ActiveBuff.of(buffType, multiplier, now, Duration.ofMillis(millis),
                SPECIAL_BUFF_SOURCE + rarity.name() + ":" + type.name());
    }

    private static GoldenRewardType pickRewardType(Map<GoldenRewardType, Integer> weights, RandomGenerator rng) {
        int total = 0;
        for (int w : weights.values()) total += w;
        int roll = rng.nextInt(total);
        for (GoldenRewardType type : GoldenRewardType.values()) {
            int w = weights.getOrDefault(type, 0);
            if (w <= 0) continue;
            if (roll < w) return type;
            roll -= w;
        }
        return GoldenRewardType.LUCKY;
    }

    /** Applies a special cookie reward: credits cookies / adds the buffs and counts the cookie. */
    public void applySpecialReward(CookieProfile profile, SpecialCookieReward reward, Instant now) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(reward, "reward");
        expireBuffs(profile, now);
        if (!reward.cookies().isZero()) profile.earn(reward.cookies());
        for (ActiveBuff buff : reward.buffs()) profile.addBuff(buff);
        profile.incrementGoldenCookiesClicked();
        profile.markDirty();
    }

    // =====================================================================================
    // Achievements
    // =====================================================================================

    /** Evaluates all achievements, adds newly unlocked ones to the profile and returns their ids. */
    public List<String> evaluateAchievements(CookieProfile profile) {
        List<String> newly = achievementEvaluator.newlySatisfied(profile.snapshot());
        for (String id : newly) profile.addAchievement(id);
        if (!newly.isEmpty()) profile.markDirty();
        return newly;
    }
}
