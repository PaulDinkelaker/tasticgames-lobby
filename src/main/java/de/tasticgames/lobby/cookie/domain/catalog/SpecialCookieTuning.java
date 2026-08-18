package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Balancing of one {@link SpecialCookieRarity}: how often it is drawn, which reward types it can roll
 * and how strong each of them is. A reward type with weight {@code 0} never rolls for this rarity, so
 * its magnitudes stay at their neutral defaults (SILVER has no chain bonus, only DIAMOND/MASTER bless).
 *
 * @param weight                 draw weight of the rarity (normalised over the unlocked rarities)
 * @param rewardWeights          relative weights of the reward types for this rarity (sum &gt; 0)
 * @param luckyBankFraction      LUCKY: fraction of the bank
 * @param luckyCpsSeconds        LUCKY: seconds of CPS the bank share is capped at
 * @param luckyFlatBonus         LUCKY: flat bonus cookies
 * @param chainBankFraction      CHAIN_BONUS: fraction of the bank
 * @param chainCpsSeconds        CHAIN_BONUS: hard ceiling in seconds of unbuffed CPS (like LUCKY, so hoarding
 *                               cannot turn a chain bonus into hours of production)
 * @param frenzyMultiplier       FRENZY: CPS and click multiplier
 * @param frenzySeconds          FRENZY: base duration
 * @param clickFrenzyMultiplier  CLICK_FRENZY: click multiplier
 * @param clickFrenzySeconds     CLICK_FRENZY: base duration
 * @param blessingCpsMultiplier  BLESSING: CPS multiplier
 * @param blessingClickMultiplier BLESSING: click multiplier
 * @param blessingSeconds        BLESSING: base duration
 */
public record SpecialCookieTuning(
        double weight,
        Map<GoldenRewardType, Integer> rewardWeights,
        double luckyBankFraction,
        long luckyCpsSeconds,
        long luckyFlatBonus,
        double chainBankFraction,
        long chainCpsSeconds,
        double frenzyMultiplier,
        int frenzySeconds,
        double clickFrenzyMultiplier,
        int clickFrenzySeconds,
        double blessingCpsMultiplier,
        double blessingClickMultiplier,
        int blessingSeconds
) {

    public SpecialCookieTuning {
        if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("rarity weight must be finite and >= 0");
        Objects.requireNonNull(rewardWeights, "rewardWeights");
        Map<GoldenRewardType, Integer> copy = new EnumMap<>(GoldenRewardType.class);
        int total = 0;
        for (Map.Entry<GoldenRewardType, Integer> entry : rewardWeights.entrySet()) {
            if (entry.getValue() < 0) throw new IllegalArgumentException("reward weight must be >= 0");
            copy.put(entry.getKey(), entry.getValue());
            total += entry.getValue();
        }
        if (total <= 0) throw new IllegalArgumentException("reward weights must sum to > 0");
        rewardWeights = Map.copyOf(copy);
        if (luckyBankFraction < 0 || chainBankFraction < 0) throw new IllegalArgumentException("bank fractions must be >= 0");
        if (chainCpsSeconds < 0) throw new IllegalArgumentException("chainCpsSeconds must be >= 0");
        if (luckyCpsSeconds < 0 || luckyFlatBonus < 0) throw new IllegalArgumentException("lucky parameters must be >= 0");
        if (frenzyMultiplier < 1 || clickFrenzyMultiplier < 1) throw new IllegalArgumentException("buff multipliers must be >= 1");
        if (blessingCpsMultiplier < 1 || blessingClickMultiplier < 1) throw new IllegalArgumentException("blessing multipliers must be >= 1");
        if (frenzySeconds <= 0 || clickFrenzySeconds <= 0 || blessingSeconds <= 0) {
            throw new IllegalArgumentException("buff durations must be > 0");
        }
    }

    /** Relative weight of one reward type ({@code 0} = the rarity never rolls it). */
    public int rewardWeight(GoldenRewardType type) {
        return rewardWeights.getOrDefault(type, 0);
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.weight = weight;
        b.rewardWeights = rewardWeights;
        b.luckyBankFraction = luckyBankFraction;
        b.luckyCpsSeconds = luckyCpsSeconds;
        b.luckyFlatBonus = luckyFlatBonus;
        b.chainBankFraction = chainBankFraction;
        b.chainCpsSeconds = chainCpsSeconds;
        b.frenzyMultiplier = frenzyMultiplier;
        b.frenzySeconds = frenzySeconds;
        b.clickFrenzyMultiplier = clickFrenzyMultiplier;
        b.clickFrenzySeconds = clickFrenzySeconds;
        b.blessingCpsMultiplier = blessingCpsMultiplier;
        b.blessingClickMultiplier = blessingClickMultiplier;
        b.blessingSeconds = blessingSeconds;
        return b;
    }

    /** The shipped tuning of every rarity (the numbers of the design table). */
    public static Map<SpecialCookieRarity, SpecialCookieTuning> defaults() {
        Map<SpecialCookieRarity, SpecialCookieTuning> map = new EnumMap<>(SpecialCookieRarity.class);
        map.put(SpecialCookieRarity.SILVER, builder(SpecialCookieRarity.SILVER)
                .rewardWeights(Map.of(GoldenRewardType.LUCKY, 50, GoldenRewardType.FRENZY, 40, GoldenRewardType.CLICK_FRENZY, 10))
                .lucky(0.05, 5 * 60L, 13)
                .frenzy(3, 30)
                .clickFrenzy(30, 12)
                .build());
        map.put(SpecialCookieRarity.GOLDEN, builder(SpecialCookieRarity.GOLDEN)
                .rewardWeights(Map.of(GoldenRewardType.LUCKY, 45, GoldenRewardType.FRENZY, 35,
                        GoldenRewardType.CLICK_FRENZY, 10, GoldenRewardType.CHAIN_BONUS, 10))
                .lucky(0.15, 15 * 60L, 13)
                .chain(0.05, 900L)
                .frenzy(7, 30)
                .clickFrenzy(777, 3)
                .build());
        map.put(SpecialCookieRarity.PLATINUM, builder(SpecialCookieRarity.PLATINUM)
                .rewardWeights(Map.of(GoldenRewardType.LUCKY, 40, GoldenRewardType.FRENZY, 35,
                        GoldenRewardType.CLICK_FRENZY, 15, GoldenRewardType.CHAIN_BONUS, 10))
                .lucky(0.25, 30 * 60L, 0)
                .chain(0.08, 1_800L)
                .frenzy(15, 45)
                .clickFrenzy(1_500, 4)
                .build());
        map.put(SpecialCookieRarity.DIAMOND, builder(SpecialCookieRarity.DIAMOND)
                .rewardWeights(Map.of(GoldenRewardType.LUCKY, 35, GoldenRewardType.FRENZY, 30,
                        GoldenRewardType.CLICK_FRENZY, 20, GoldenRewardType.BLESSING, 15))
                .lucky(0.40, 45 * 60L, 0)
                .frenzy(30, 60)
                .clickFrenzy(2_000, 5)
                .blessing(30, 100, 30)
                .build());
        map.put(SpecialCookieRarity.MASTER, builder(SpecialCookieRarity.MASTER)
                .rewardWeights(Map.of(GoldenRewardType.LUCKY, 30, GoldenRewardType.FRENZY, 25,
                        GoldenRewardType.CLICK_FRENZY, 20, GoldenRewardType.BLESSING, 25))
                .lucky(0.77, 90 * 60L, 0)
                .frenzy(77, 77)
                .clickFrenzy(7_777, 3)
                .blessing(77, 250, 45)
                .build());
        return Map.copyOf(map);
    }

    /** Builder starting at the rarity's catalog weight and otherwise neutral values. */
    public static Builder builder(SpecialCookieRarity rarity) {
        Builder b = new Builder();
        b.weight = Objects.requireNonNull(rarity, "rarity").baseWeight();
        return b;
    }

    /** Mutable builder; unused reward magnitudes stay at their neutral defaults. */
    public static final class Builder {
        private double weight = 1;
        private Map<GoldenRewardType, Integer> rewardWeights = Map.of(GoldenRewardType.LUCKY, 1);
        private double luckyBankFraction = 0.15;
        private long luckyCpsSeconds = 15 * 60L;
        private long luckyFlatBonus;
        private double chainBankFraction;
        private long chainCpsSeconds;
        private double frenzyMultiplier = 1;
        private int frenzySeconds = 30;
        private double clickFrenzyMultiplier = 1;
        private int clickFrenzySeconds = 13;
        private double blessingCpsMultiplier = 1;
        private double blessingClickMultiplier = 1;
        private int blessingSeconds = 30;

        private Builder() {
        }

        public Builder weight(double v) { this.weight = v; return this; }
        public Builder rewardWeights(Map<GoldenRewardType, Integer> v) { this.rewardWeights = v; return this; }
        public Builder lucky(double bankFraction, long cpsSeconds, long flatBonus) {
            this.luckyBankFraction = bankFraction;
            this.luckyCpsSeconds = cpsSeconds;
            this.luckyFlatBonus = flatBonus;
            return this;
        }
        public Builder luckyBankFraction(double v) { this.luckyBankFraction = v; return this; }
        public Builder luckyCpsSeconds(long v) { this.luckyCpsSeconds = v; return this; }
        public Builder luckyFlatBonus(long v) { this.luckyFlatBonus = v; return this; }
        public Builder chainBankFraction(double v) { this.chainBankFraction = v; return this; }
        public Builder chainCpsSeconds(long v) { this.chainCpsSeconds = v; return this; }

        /** CHAIN_BONUS: bank fraction plus its ceiling in seconds of unbuffed CPS (0 = uncapped). */
        public Builder chain(double fraction, long cpsSeconds) {
            this.chainBankFraction = fraction;
            this.chainCpsSeconds = cpsSeconds;
            return this;
        }
        public Builder frenzy(double multiplier, int seconds) {
            this.frenzyMultiplier = multiplier;
            this.frenzySeconds = seconds;
            return this;
        }
        public Builder frenzyMultiplier(double v) { this.frenzyMultiplier = v; return this; }
        public Builder frenzySeconds(int v) { this.frenzySeconds = v; return this; }
        public Builder clickFrenzy(double multiplier, int seconds) {
            this.clickFrenzyMultiplier = multiplier;
            this.clickFrenzySeconds = seconds;
            return this;
        }
        public Builder clickFrenzyMultiplier(double v) { this.clickFrenzyMultiplier = v; return this; }
        public Builder clickFrenzySeconds(int v) { this.clickFrenzySeconds = v; return this; }
        public Builder blessing(double cpsMultiplier, double clickMultiplier, int seconds) {
            this.blessingCpsMultiplier = cpsMultiplier;
            this.blessingClickMultiplier = clickMultiplier;
            this.blessingSeconds = seconds;
            return this;
        }
        public Builder blessingCpsMultiplier(double v) { this.blessingCpsMultiplier = v; return this; }
        public Builder blessingClickMultiplier(double v) { this.blessingClickMultiplier = v; return this; }
        public Builder blessingSeconds(int v) { this.blessingSeconds = v; return this; }

        public SpecialCookieTuning build() {
            return new SpecialCookieTuning(weight, rewardWeights, luckyBankFraction, luckyCpsSeconds, luckyFlatBonus,
                    chainBankFraction, chainCpsSeconds, frenzyMultiplier, frenzySeconds, clickFrenzyMultiplier, clickFrenzySeconds,
                    blessingCpsMultiplier, blessingClickMultiplier, blessingSeconds);
        }
    }
}
