package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;

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
 * @param specialMinIntervalSeconds  shortest drawn wait between two special cookies at chance multiplier 1
 * @param specialMaxIntervalSeconds  longest drawn wait between two special cookies at chance multiplier 1
 * @param specialFloorIntervalSeconds hard lower bound of the wait, however high the chance multiplier gets
 * @param specialLifetimeSeconds     seconds a special cookie spawned in the world stays clickable
 * @param specialClickClampCpsSeconds while a special click buff is active a single click can never pay more than
 *                               this many seconds of unbuffed CPS (0 = no clamp). Without it a x7777 click buff
 *                               plus an autoclicker is worth hours of production per activation.
 * @param rarities                   per rarity balancing (weight, reward weights, magnitudes, durations)
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
        long specialMinIntervalSeconds,
        long specialMaxIntervalSeconds,
        long specialFloorIntervalSeconds,
        int specialLifetimeSeconds,
        long specialClickClampCpsSeconds,
        Map<SpecialCookieRarity, SpecialCookieTuning> rarities
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
        if (specialMinIntervalSeconds <= 0) throw new IllegalArgumentException("specialMinIntervalSeconds must be > 0");
        if (specialMaxIntervalSeconds < specialMinIntervalSeconds) {
            throw new IllegalArgumentException("specialMaxIntervalSeconds must be >= specialMinIntervalSeconds");
        }
        if (specialFloorIntervalSeconds <= 0) throw new IllegalArgumentException("specialFloorIntervalSeconds must be > 0");
        if (specialLifetimeSeconds <= 0) throw new IllegalArgumentException("specialLifetimeSeconds must be > 0");
        if (specialClickClampCpsSeconds < 0) throw new IllegalArgumentException("specialClickClampCpsSeconds must be >= 0");
        Objects.requireNonNull(rarities, "rarities");
        Map<SpecialCookieRarity, SpecialCookieTuning> tunings = new EnumMap<>(SpecialCookieRarity.class);
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            SpecialCookieTuning tuning = rarities.get(rarity);
            if (tuning == null) throw new IllegalArgumentException("Missing balancing for rarity " + rarity);
            tunings.put(rarity, tuning);
        }
        rarities = Map.copyOf(tunings);
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
        b.specialMinIntervalSeconds = specialMinIntervalSeconds;
        b.specialMaxIntervalSeconds = specialMaxIntervalSeconds;
        b.specialFloorIntervalSeconds = specialFloorIntervalSeconds;
        b.specialLifetimeSeconds = specialLifetimeSeconds;
        b.specialClickClampCpsSeconds = specialClickClampCpsSeconds;
        b.rarities = rarities;
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

    /** Balancing of one rarity (never null – the constructor requires every rarity). */
    public SpecialCookieTuning tuning(SpecialCookieRarity rarity) {
        return rarities.get(Objects.requireNonNull(rarity, "rarity"));
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
        private long specialMinIntervalSeconds = 15L * 60L;
        private long specialMaxIntervalSeconds = 120L * 60L;
        private long specialFloorIntervalSeconds = 5L * 60L;
        private int specialLifetimeSeconds = 45;
        private long specialClickClampCpsSeconds = 60L;
        private Map<SpecialCookieRarity, SpecialCookieTuning> rarities = SpecialCookieTuning.defaults();

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
        public Builder specialMinIntervalSeconds(long v) { this.specialMinIntervalSeconds = v; return this; }
        public Builder specialMaxIntervalSeconds(long v) { this.specialMaxIntervalSeconds = v; return this; }
        public Builder specialFloorIntervalSeconds(long v) { this.specialFloorIntervalSeconds = v; return this; }
        public Builder specialLifetimeSeconds(int v) { this.specialLifetimeSeconds = v; return this; }
        public Builder specialClickClampCpsSeconds(long v) { this.specialClickClampCpsSeconds = v; return this; }
        public Builder rarities(Map<SpecialCookieRarity, SpecialCookieTuning> v) { this.rarities = v; return this; }

        /** Replaces the tuning of a single rarity, starting from the value currently held. */
        public Builder rarity(SpecialCookieRarity rarity, SpecialCookieTuning tuning) {
            Map<SpecialCookieRarity, SpecialCookieTuning> copy = new EnumMap<>(SpecialCookieRarity.class);
            copy.putAll(rarities);
            copy.put(Objects.requireNonNull(rarity, "rarity"), Objects.requireNonNull(tuning, "tuning"));
            this.rarities = copy;
            return this;
        }

        /** Current tuning of a rarity, e.g. to derive an override via {@link SpecialCookieTuning#toBuilder()}. */
        public SpecialCookieTuning rarity(SpecialCookieRarity rarity) {
            return rarities.get(Objects.requireNonNull(rarity, "rarity"));
        }

        public CookieBalancing build() {
            return new CookieBalancing(costGrowth, comboStages, clicksPerComboStage, comboWindowMillis, comboDecayMillis,
                    maxClicksPerSecond, offlineEnabled, offlineMaxSeconds, offlineEfficiency, specialMinIntervalSeconds,
                    specialMaxIntervalSeconds, specialFloorIntervalSeconds, specialLifetimeSeconds,
                    specialClickClampCpsSeconds, rarities);
        }
    }
}
