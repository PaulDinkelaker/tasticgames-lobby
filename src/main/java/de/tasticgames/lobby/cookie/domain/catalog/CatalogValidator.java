package de.tasticgames.lobby.cookie.domain.catalog;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates catalog content. Throws {@link IllegalArgumentException} with a descriptive message
 * on the first violation found.
 */
public final class CatalogValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)*");

    private CatalogValidator() {
    }

    public static void validate(List<GeneratorDefinition> generators,
                                List<UpgradeDefinition> upgrades,
                                List<PrestigeDefinition> prestiges,
                                List<PrestigeTreeNode> tree,
                                List<ZoneDefinition> zones,
                                List<AchievementDefinition> achievements) {
        validateGenerators(generators);
        Set<String> generatorIds = new HashSet<>();
        generators.forEach(g -> generatorIds.add(g.id()));
        validateUpgrades(upgrades, generatorIds);
        validateZones(zones);
        Set<String> zoneIds = new HashSet<>();
        zones.forEach(z -> zoneIds.add(z.id()));
        validatePrestiges(prestiges, generatorIds, zoneIds);
        validateTree(tree, generatorIds);
        validateAchievements(achievements);
    }

    static void validateGenerators(List<GeneratorDefinition> generators) {
        if (generators.isEmpty()) throw new IllegalArgumentException("Catalog must define at least one generator");
        Set<String> ids = new HashSet<>();
        for (GeneratorDefinition g : generators) {
            requireId("generator", g.id());
            if (!ids.add(g.id())) throw new IllegalArgumentException("Duplicate generator id: " + g.id());
            if (!g.baseCost().isPositive()) throw new IllegalArgumentException("Generator " + g.id() + " must have a positive baseCost");
            if (g.baseCps().signum() <= 0) throw new IllegalArgumentException("Generator " + g.id() + " must have a positive baseCps");
            if (g.unlockPrestige() < 0) throw new IllegalArgumentException("Generator " + g.id() + " has negative unlockPrestige");
            if (g.milestoneMultiplier() < 1.0) throw new IllegalArgumentException("Generator " + g.id() + " milestoneMultiplier must be >= 1");
            List<Integer> ms = g.milestoneCounts();
            for (int i = 0; i < ms.size(); i++) {
                if (ms.get(i) <= 0) throw new IllegalArgumentException("Generator " + g.id() + " milestone counts must be positive");
                if (i > 0 && ms.get(i) <= ms.get(i - 1)) {
                    throw new IllegalArgumentException("Generator " + g.id() + " milestone counts must be strictly ascending");
                }
            }
        }
    }

    static void validateUpgrades(List<UpgradeDefinition> upgrades, Set<String> generatorIds) {
        Set<String> ids = new HashSet<>();
        for (UpgradeDefinition u : upgrades) {
            requireId("upgrade", u.id());
            if (!ids.add(u.id())) throw new IllegalArgumentException("Duplicate upgrade id: " + u.id());
            if (!u.cost().isPositive()) throw new IllegalArgumentException("Upgrade " + u.id() + " must have a positive cost");
            if (u.unlockPrestige() < 0) throw new IllegalArgumentException("Upgrade " + u.id() + " has negative unlockPrestige");
            if (u.requiredGeneratorId() != null && !generatorIds.contains(u.requiredGeneratorId())) {
                throw new IllegalArgumentException("Upgrade " + u.id() + " requires unknown generator " + u.requiredGeneratorId());
            }
            if (u.requiredCount() < 0) throw new IllegalArgumentException("Upgrade " + u.id() + " has negative requiredCount");
            UpgradeEffect e = u.effect();
            switch (e.type()) {
                case CLICK_POWER_MULTIPLIER, GLOBAL_CPS_MULTIPLIER, GENERATOR_MULTIPLIER, GOLDEN_COOKIE_FREQUENCY,
                     GOLDEN_COOKIE_VALUE, SPECIAL_RARITY_WEIGHT, COMBO_DURATION -> {
                    if (e.value() <= 0) throw new IllegalArgumentException("Upgrade " + u.id() + " multiplier must be > 0");
                }
                case CLICK_POWER_ADD_CPS_PERCENT, OFFLINE_EFFICIENCY -> {
                    if (e.value() < 0) throw new IllegalArgumentException("Upgrade " + u.id() + " effect value must be >= 0");
                }
            }
            if (e.type() == UpgradeEffectType.GENERATOR_MULTIPLIER && !generatorIds.contains(e.generatorId())) {
                throw new IllegalArgumentException("Upgrade " + u.id() + " targets unknown generator " + e.generatorId());
            }
        }
    }

    static void validatePrestiges(List<PrestigeDefinition> prestiges, Set<String> generatorIds, Set<String> zoneIds) {
        if (prestiges.isEmpty()) throw new IllegalArgumentException("Catalog must define at least prestige level 0");
        if (prestiges.getFirst().level() != 0) throw new IllegalArgumentException("Prestige levels must start at 0");
        BigDecimal lastThreshold = null;
        double lastMultiplier = 0;
        Set<String> cosmetics = new HashSet<>();
        for (int i = 0; i < prestiges.size(); i++) {
            PrestigeDefinition p = prestiges.get(i);
            if (p.level() != i) throw new IllegalArgumentException("Prestige levels must be contiguous, found level " + p.level() + " at index " + i);
            if (p.requiredLifetimeCookies().signum() < 0) throw new IllegalArgumentException("Prestige " + i + " threshold must be >= 0");
            if (i == 0 && p.requiredLifetimeCookies().signum() != 0) {
                throw new IllegalArgumentException("Prestige 0 threshold must be 0");
            }
            if (lastThreshold != null && p.requiredLifetimeCookies().compareTo(lastThreshold) <= 0) {
                throw new IllegalArgumentException("Prestige thresholds must be strictly increasing (level " + i + ")");
            }
            if (p.totalMultiplier() <= 0) throw new IllegalArgumentException("Prestige " + i + " multiplier must be > 0");
            if (p.totalMultiplier() < lastMultiplier) {
                throw new IllegalArgumentException("Prestige multipliers must be non-decreasing (level " + i + ")");
            }
            if (!zoneIds.contains(p.unlockedZoneId())) {
                throw new IllegalArgumentException("Prestige " + i + " unlocks unknown zone " + p.unlockedZoneId());
            }
            for (String g : p.unlockedGeneratorIds()) {
                if (!generatorIds.contains(g)) throw new IllegalArgumentException("Prestige " + i + " unlocks unknown generator " + g);
            }
            for (String c : p.rewardCosmeticIds()) {
                if (!cosmetics.add(c)) throw new IllegalArgumentException("Cosmetic " + c + " is rewarded by more than one prestige level");
            }
            lastThreshold = p.requiredLifetimeCookies();
            lastMultiplier = p.totalMultiplier();
        }
    }

    static void validateTree(List<PrestigeTreeNode> tree, Set<String> generatorIds) {
        Set<String> ids = new HashSet<>();
        for (PrestigeTreeNode n : tree) {
            requireId("prestige tree node", n.id());
            if (!ids.add(n.id())) throw new IllegalArgumentException("Duplicate prestige tree node id: " + n.id());
            if (n.valuePerLevel() < 0) throw new IllegalArgumentException("Tree node " + n.id() + " valuePerLevel must be >= 0");
            if (n.effect() == PrestigeTreeEffectType.STARTING_GENERATORS && !generatorIds.contains(n.targetGeneratorId())) {
                throw new IllegalArgumentException("Tree node " + n.id() + " targets unknown generator " + n.targetGeneratorId());
            }
        }
    }

    static void validateZones(List<ZoneDefinition> zones) {
        if (zones.isEmpty()) throw new IllegalArgumentException("Catalog must define at least one zone");
        Set<String> ids = new HashSet<>();
        for (ZoneDefinition z : zones) {
            requireId("zone", z.id());
            if (!ids.add(z.id())) throw new IllegalArgumentException("Duplicate zone id: " + z.id());
            if (z.minPrestige() < 0) throw new IllegalArgumentException("Zone " + z.id() + " has negative minPrestige");
        }
    }

    static void validateAchievements(List<AchievementDefinition> achievements) {
        Set<String> ids = new HashSet<>();
        for (AchievementDefinition a : achievements) {
            requireId("achievement", a.id());
            if (!ids.add(a.id())) throw new IllegalArgumentException("Duplicate achievement id: " + a.id());
            if (a.condition().threshold().signum() < 0) {
                throw new IllegalArgumentException("Achievement " + a.id() + " threshold must be >= 0");
            }
        }
    }

    private static void requireId(String kind, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Blank " + kind + " id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid " + kind + " id '" + id + "' (expected snake_case)");
        }
    }
}
