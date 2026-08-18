package de.tasticgames.lobby.cookie.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable computed statistics of a profile at one instant.
 *
 * @param clickValue                cookies per (non-combo) click, including click upgrades, tree, prestige and click buffs
 * @param baseClickValue            cookies per click without buffs and without combo
 * @param baseCps                   sum of generator output including per-generator upgrades and milestones, before global multipliers
 * @param unbuffedCps               CPS with all permanent multipliers, but without active buffs (used for offline)
 * @param effectiveCps              CPS including active buffs
 * @param contributions             per generator effective contribution (sums to {@code effectiveCps})
 * @param prestigeMultiplier        multiplier of the current prestige level
 * @param upgradeCpsMultiplier      product of GLOBAL_CPS_MULTIPLIER upgrades
 * @param treeCpsMultiplier         1 + tree GLOBAL_CPS_PERCENT / 100
 * @param upgradeClickMultiplier    product of CLICK_POWER_MULTIPLIER upgrades
 * @param treeClickMultiplier       1 + tree CLICK_POWER_PERCENT / 100
 * @param clickCpsPercent           sum of CLICK_POWER_ADD_CPS_PERCENT upgrades (percent)
 * @param buffCpsMultiplier         product of active buffs affecting CPS
 * @param buffClickMultiplier       product of active buffs affecting clicks
 * @param offlineEfficiency         offline efficiency fraction (capped at 1.0)
 * @param goldenChanceMultiplier    special cookie chance multiplier (divides the drawn wait)
 * @param goldenValueMultiplier     special cookie instant reward multiplier
 * @param goldenDurationMultiplier  special cookie buff duration multiplier
 * @param comboDurationMultiplier   combo decay duration multiplier
 * @param specialWeightMultipliers  per rarity draw-weight multiplier from upgrades and the prestige tree
 */
public record CookieStats(
        BigDecimal clickValue,
        BigDecimal baseClickValue,
        BigDecimal baseCps,
        BigDecimal unbuffedCps,
        BigDecimal effectiveCps,
        List<Contribution> contributions,
        double prestigeMultiplier,
        double upgradeCpsMultiplier,
        double treeCpsMultiplier,
        double upgradeClickMultiplier,
        double treeClickMultiplier,
        double clickCpsPercent,
        double buffCpsMultiplier,
        double buffClickMultiplier,
        double offlineEfficiency,
        double goldenChanceMultiplier,
        double goldenValueMultiplier,
        double goldenDurationMultiplier,
        double comboDurationMultiplier,
        Map<SpecialCookieRarity, Double> specialWeightMultipliers
) {

    public CookieStats {
        Objects.requireNonNull(clickValue, "clickValue");
        Objects.requireNonNull(baseClickValue, "baseClickValue");
        Objects.requireNonNull(baseCps, "baseCps");
        Objects.requireNonNull(unbuffedCps, "unbuffedCps");
        Objects.requireNonNull(effectiveCps, "effectiveCps");
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        specialWeightMultipliers = Map.copyOf(Objects.requireNonNull(specialWeightMultipliers, "specialWeightMultipliers"));
    }

    /** Effective CPS as an amount (display convenience). */
    public CookieAmount effectiveCpsAmount() {
        return CookieAmount.of(effectiveCps);
    }

    /** Draw-weight multiplier of one rarity ({@code 1.0} when nothing boosts it). */
    public double specialWeightMultiplier(SpecialCookieRarity rarity) {
        return specialWeightMultipliers.getOrDefault(rarity, 1.0);
    }
}
