package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tunable balancing knobs of the engine. Immutable; a plugin loads these from its config and
 * passes them to the engine together with the {@link CookieCatalog}.
 *
 * @param costGrowth                 geometric cost growth per owned generator unit (default 1.15)
 * @param comboStages                combo multiplier per stage, index 0 = no combo (default 1.0, 1.1, 1.25, 1.5, 2.0)
 * @param clicksPerComboStage        rapid clicks needed to advance one combo stage
 * @param comboWindowMillis          max gap between two clicks for them to count as "rapid"
 * @param comboDecayMillis           idle time after which the combo resets to stage 0
 * @param maxClicksPerSecond         server-side click-rate cap; clicks above it earn nothing
 * @param offlineEnabled             whether offline production is granted
 * @param offlineMaxSeconds          maximum offline seconds credited
 * @param offlineEfficiency          base offline efficiency fraction (0.5 = 50%)
 * @param goldenBaseIntervalSeconds  mean seconds between golden cookie spawns at chance multiplier 1
 * @param goldenLifetimeSeconds      seconds a spawned golden cookie stays clickable
 * @param goldenBuffSeconds          base duration of golden buffs (Frenzy)
 * @param goldenClickFrenzySeconds   base duration of the Click Frenzy buff
 * @param goldenFrenzyMultiplier     Frenzy multiplier (CPS and clicks)
 * @param goldenClickFrenzyMultiplier Click Frenzy click multiplier
 * @param luckyBankFraction          Lucky reward: fraction of the bank
 * @param luckyCpsSeconds            Lucky reward: seconds of CPS cap
 * @param luckyFlatBonus             Lucky reward: flat bonus cookies
 * @param chainBonusBankFraction     Chain bonus: fraction of the bank
 * @param goldenRewardWeights        relative weights of golden reward types
 */
public record CookieBalancing(
        double costGrowth,
        List<Double> comboStages,
        int clicksPerComboStage,
        long comboWindowMillis,
        long comboDecayMillis,
        int maxClicksPerSecond,
        boolean offlineEnabled,
        long offlineMaxSeconds,
        double offlineEfficiency,
        double goldenBaseIntervalSeconds,
        int goldenLifetimeSeconds,
        int goldenBuffSeconds,
        int goldenClickFrenzySeconds,
        double goldenFrenzyMultiplier,
        double goldenClickFrenzyMultiplier,
        double luckyBankFraction,
        long luckyCpsSeconds,
        long luckyFlatBonus,
        double chainBonusBankFraction,
        Map<GoldenRewardType, Integer> goldenRewardWeights
) {

    public CookieBalancing {
        if (costGrowth <= 1.0) throw new IllegalArgumentException("costGrowth must be > 1.0");
        comboStages = List.copyOf(Objects.requireNonNull(comboStages, "comboStages"));
        if (comboStages.isEmpty()) throw new IllegalArgumentException("comboStages must not be empty");
        if (comboStages.getFirst() != 1.0) throw new IllegalArgumentException("comboStages[0] must be 1.0");
        for (int i = 1; i < comboStages.size(); i++) {
            if (comboStages.get(i) < comboStages.get(i - 1)) {
                throw new IllegalArgumentException("comboStages must be non-decreasing");
            }
        }
        if (clicksPerComboStage < 1) throw new IllegalArgumentException("clicksPerComboStage must be >= 1");
        if (comboWindowMillis <= 0) throw new IllegalArgumentException("comboWindowMillis must be > 0");
        if (comboDecayMillis < comboWindowMillis) throw new IllegalArgumentException("comboDecayMillis must be >= comboWindowMillis");
        if (maxClicksPerSecond < 1) throw new IllegalArgumentException("maxClicksPerSecond must be >= 1");
        if (offlineMaxSeconds < 0) throw new IllegalArgumentException("offlineMaxSeconds must be >= 0");
        if (offlineEfficiency < 0 || offlineEfficiency > 1) throw new IllegalArgumentException("offlineEfficiency must be within [0,1]");
        if (goldenBaseIntervalSeconds <= 0) throw new IllegalArgumentException("goldenBaseIntervalSeconds must be > 0");
        if (goldenLifetimeSeconds <= 0) throw new IllegalArgumentException("goldenLifetimeSeconds must be > 0");
        if (goldenBuffSeconds <= 0) throw new IllegalArgumentException("goldenBuffSeconds must be > 0");
        if (goldenClickFrenzySeconds <= 0) throw new IllegalArgumentException("goldenClickFrenzySeconds must be > 0");
        if (goldenFrenzyMultiplier < 1) throw new IllegalArgumentException("goldenFrenzyMultiplier must be >= 1");
        if (goldenClickFrenzyMultiplier < 1) throw new IllegalArgumentException("goldenClickFrenzyMultiplier must be >= 1");
        if (luckyBankFraction < 0 || chainBonusBankFraction < 0) throw new IllegalArgumentException("bank fractions must be >= 0");
        if (luckyCpsSeconds < 0 || luckyFlatBonus < 0) throw new IllegalArgumentException("lucky parameters must be >= 0");
        Objects.requireNonNull(goldenRewardWeights, "goldenRewardWeights");
        Map<GoldenRewardType, Integer> weights = new EnumMap<>(GoldenRewardType.class);
        int total = 0;
        for (Map.Entry<GoldenRewardType, Integer> e : goldenRewardWeights.entrySet()) {
            if (e.getValue() < 0) throw new IllegalArgumentException("golden weight must be >= 0");
            weights.put(e.getKey(), e.getValue());
            total += e.getValue();
        }
        if (total <= 0) throw new IllegalArgumentException("golden reward weights must sum to > 0");
        goldenRewardWeights = Map.copyOf(weights);
    }

    public static CookieBalancing defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.costGrowth = costGrowth;
        b.comboStages = comboStages;
        b.clicksPerComboStage = clicksPerComboStage;
        b.comboWindowMillis = comboWindowMillis;
        b.comboDecayMillis = comboDecayMillis;
        b.maxClicksPerSecond = maxClicksPerSecond;
        b.offlineEnabled = offlineEnabled;
        b.offlineMaxSeconds = offlineMaxSeconds;
        b.offlineEfficiency = offlineEfficiency;
        b.goldenBaseIntervalSeconds = goldenBaseIntervalSeconds;
        b.goldenLifetimeSeconds = goldenLifetimeSeconds;
        b.goldenBuffSeconds = goldenBuffSeconds;
        b.goldenClickFrenzySeconds = goldenClickFrenzySeconds;
        b.goldenFrenzyMultiplier = goldenFrenzyMultiplier;
        b.goldenClickFrenzyMultiplier = goldenClickFrenzyMultiplier;
        b.luckyBankFraction = luckyBankFraction;
        b.luckyCpsSeconds = luckyCpsSeconds;
        b.luckyFlatBonus = luckyFlatBonus;
        b.chainBonusBankFraction = chainBonusBankFraction;
        b.goldenRewardWeights = goldenRewardWeights;
        return b;
    }

    /** Highest combo stage index. */
    public int maxComboStage() {
        return comboStages.size() - 1;
    }

    /** Multiplier of the given stage (clamped). */
    public double comboMultiplier(int stage) {
        int idx = Math.max(0, Math.min(stage, maxComboStage()));
        return comboStages.get(idx);
    }

    /** Mutable builder; every field starts at its default value. */
    public static final class Builder {
        private double costGrowth = 1.15;
        private List<Double> comboStages = List.of(1.0, 1.1, 1.25, 1.5, 2.0);
        private int clicksPerComboStage = 5;
        private long comboWindowMillis = 800;
        private long comboDecayMillis = 3000;
        private int maxClicksPerSecond = 15;
        private boolean offlineEnabled = true;
        private long offlineMaxSeconds = 8L * 3600L;
        private double offlineEfficiency = 0.5;
        private double goldenBaseIntervalSeconds = 300;
        private int goldenLifetimeSeconds = 15;
        private int goldenBuffSeconds = 30;
        private int goldenClickFrenzySeconds = 13;
        private double goldenFrenzyMultiplier = 7;
        private double goldenClickFrenzyMultiplier = 777;
        private double luckyBankFraction = 0.15;
        private long luckyCpsSeconds = 15L * 60L;
        private long luckyFlatBonus = 13;
        private double chainBonusBankFraction = 0.05;
        private Map<GoldenRewardType, Integer> goldenRewardWeights = Map.of(
                GoldenRewardType.LUCKY, 45,
                GoldenRewardType.FRENZY, 35,
                GoldenRewardType.CLICK_FRENZY, 10,
                GoldenRewardType.CHAIN_BONUS, 10);

        private Builder() {
        }

        public Builder costGrowth(double v) { this.costGrowth = v; return this; }
        public Builder comboStages(List<Double> v) { this.comboStages = v; return this; }
        public Builder clicksPerComboStage(int v) { this.clicksPerComboStage = v; return this; }
        public Builder comboWindowMillis(long v) { this.comboWindowMillis = v; return this; }
        public Builder comboDecayMillis(long v) { this.comboDecayMillis = v; return this; }
        public Builder maxClicksPerSecond(int v) { this.maxClicksPerSecond = v; return this; }
        public Builder offlineEnabled(boolean v) { this.offlineEnabled = v; return this; }
        public Builder offlineMaxSeconds(long v) { this.offlineMaxSeconds = v; return this; }
        public Builder offlineEfficiency(double v) { this.offlineEfficiency = v; return this; }
        public Builder goldenBaseIntervalSeconds(double v) { this.goldenBaseIntervalSeconds = v; return this; }
        public Builder goldenLifetimeSeconds(int v) { this.goldenLifetimeSeconds = v; return this; }
        public Builder goldenBuffSeconds(int v) { this.goldenBuffSeconds = v; return this; }
        public Builder goldenClickFrenzySeconds(int v) { this.goldenClickFrenzySeconds = v; return this; }
        public Builder goldenFrenzyMultiplier(double v) { this.goldenFrenzyMultiplier = v; return this; }
        public Builder goldenClickFrenzyMultiplier(double v) { this.goldenClickFrenzyMultiplier = v; return this; }
        public Builder luckyBankFraction(double v) { this.luckyBankFraction = v; return this; }
        public Builder luckyCpsSeconds(long v) { this.luckyCpsSeconds = v; return this; }
        public Builder luckyFlatBonus(long v) { this.luckyFlatBonus = v; return this; }
        public Builder chainBonusBankFraction(double v) { this.chainBonusBankFraction = v; return this; }
        public Builder goldenRewardWeights(Map<GoldenRewardType, Integer> v) { this.goldenRewardWeights = v; return this; }

        public CookieBalancing build() {
            return new CookieBalancing(costGrowth, comboStages, clicksPerComboStage, comboWindowMillis, comboDecayMillis,
                    maxClicksPerSecond, offlineEnabled, offlineMaxSeconds, offlineEfficiency, goldenBaseIntervalSeconds,
                    goldenLifetimeSeconds, goldenBuffSeconds, goldenClickFrenzySeconds, goldenFrenzyMultiplier,
                    goldenClickFrenzyMultiplier, luckyBankFraction, luckyCpsSeconds, luckyFlatBonus,
                    chainBonusBankFraction, goldenRewardWeights);
        }
    }
}
