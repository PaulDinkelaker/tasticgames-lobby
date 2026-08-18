package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.catalog.AchievementCondition.Type;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default, config-equivalent content of the Cookie Clicker minigame. All ids are stable.
 * See {@code BALANCING.md} for the reasoning behind the numbers.
 */
public final class DefaultCatalog {

    private DefaultCatalog() {
    }

    // ------------------------------------------------------------------ generators

    public static List<GeneratorDefinition> generators() {
        return List.of(
                // P0 generators: baseCps lowered from the classic 0.1 / 1 / 8 so that the first prestige
                // takes >= 30 min of active play (see BALANCING.md); costs unchanged.
                GeneratorDefinition.of("cursor", 0, 15L, "0.05", 0),
                GeneratorDefinition.of("baker", 1, 100L, "0.3", 0),
                GeneratorDefinition.of("oven", 2, 1_100L, "2.5", 0),
                GeneratorDefinition.of("sugar_farm", 3, 12_000L, "47", 1),
                GeneratorDefinition.of("cocoa_mine", 4, 130_000L, "260", 2),
                GeneratorDefinition.of("cookie_factory", 5, 1_400_000L, "1400", 3),
                GeneratorDefinition.of("alchemy_kitchen", 6, 20_000_000L, "7800", 4),
                GeneratorDefinition.of("portal_bakery", 7, 330_000_000L, "44000", 6),
                GeneratorDefinition.of("time_oven", 8, 5_100_000_000L, "260000", 7),
                GeneratorDefinition.of("galactic_bakery", 9, 75_000_000_000L, "1600000", 8),
                GeneratorDefinition.of("reality_forge", 10, 1_200_000_000_000L, "10000000", 9)
        );
    }

    // ------------------------------------------------------------------ upgrades

    /**
     * Prestige-gated global "recipe" upgrades. They are re-bought every run and are the main lever
     * that lets the economy keep pace with the x1000/level prestige thresholds (see BALANCING.md).
     * Index = prestige level at which the recipe unlocks (index 0 unused).
     */
    static final double[] RECIPE_MULTIPLIERS = {0, 2, 12, 20, 60, 900, 900, 90, 900, 90};
    static final String[] RECIPE_IDS = {null,
            "sugar_awakening_recipe", "cocoa_frontier_recipe", "industrial_recipe", "arcane_recipe",
            "royal_recipe", "dimensional_recipe", "chrono_recipe", "stellar_recipe", "reality_recipe"};
    /** Recipe cost = previous prestige threshold / RECIPE_COST_DIVISOR. */
    static final BigDecimal RECIPE_COST_DIVISOR = BigDecimal.valueOf(50);

    /** Exclusive group of the three baking styles. */
    public static final String STYLE_GROUP = "baking_style";

    public static List<UpgradeDefinition> upgrades() {
        List<UpgradeDefinition> list = new ArrayList<>();

        // Click upgrades
        list.add(UpgradeDefinition.of("reinforced_index_finger", 100L, UpgradeEffect.clickMultiplier(2)));
        list.add(UpgradeDefinition.of("carpal_tunnel", 500L, UpgradeEffect.clickMultiplier(2)));
        list.add(UpgradeDefinition.of("ambidextrous", 10_000L, UpgradeEffect.clickMultiplier(2)));
        list.add(UpgradeDefinition.of("thousand_fingers", 100_000L, UpgradeEffect.clickAddCpsPercent(1)));
        list.add(UpgradeDefinition.of("million_fingers", 10_000_000L, UpgradeEffect.clickAddCpsPercent(1)).unlockedAtPrestige(1));

        // Two tiers per generator: owned >= 1 (10x base cost) and owned >= 10 (100x base cost), each x2
        for (GeneratorDefinition g : generators()) {
            long base = g.baseCost().toBigInteger().longValueExact();
            list.add(UpgradeDefinition.of(g.id() + "_tier_1", base * 10L, UpgradeEffect.generatorMultiplier(g.id(), 2))
                    .requiring(g.id(), 1).unlockedAtPrestige(g.unlockPrestige()));
            list.add(UpgradeDefinition.of(g.id() + "_tier_2", base * 100L, UpgradeEffect.generatorMultiplier(g.id(), 2))
                    .requiring(g.id(), 10).unlockedAtPrestige(g.unlockPrestige()));
        }

        // Baking styles: exactly one per run, bought once, and they steer how the run plays. The choice is
        // cleared by a prestige together with the other upgrades, so every run can be played differently.
        list.add(UpgradeDefinition.of("style_artisan", 250_000L, UpgradeEffect.clickMultiplier(4))
                .unlockedAtPrestige(1).exclusiveIn(STYLE_GROUP));
        list.add(UpgradeDefinition.of("style_industrial", 250_000L, UpgradeEffect.globalCps(1.4))
                .unlockedAtPrestige(1).exclusiveIn(STYLE_GROUP));
        list.add(UpgradeDefinition.of("style_lucky", 250_000L, UpgradeEffect.goldenFrequency(2.0))
                .unlockedAtPrestige(1).exclusiveIn(STYLE_GROUP));

        // Utility upgrades
        list.add(UpgradeDefinition.of("golden_luck", 500_000L, UpgradeEffect.goldenFrequency(1.5)));
        list.add(UpgradeDefinition.of("golden_glow", 5_000_000L, UpgradeEffect.goldenValue(2)));

        // Special cookie rarity upgrades: one per rarity, unlocked with the rarity itself
        list.add(UpgradeDefinition.of("refined_sugar", 100_000L,
                UpgradeEffect.rarityWeight(SpecialCookieRarity.GOLDEN, 1.5)).unlockedAtPrestige(1));
        list.add(UpgradeDefinition.of("platinum_press", 100_000_000L,
                UpgradeEffect.rarityWeight(SpecialCookieRarity.PLATINUM, 1.5)).unlockedAtPrestige(2));
        list.add(UpgradeDefinition.of("diamond_cutter", "1e14",
                UpgradeEffect.rarityWeight(SpecialCookieRarity.DIAMOND, 1.5)).unlockedAtPrestige(4));
        list.add(UpgradeDefinition.of("master_recipe_book", "1e25",
                UpgradeEffect.rarityWeight(SpecialCookieRarity.MASTER, 2.0)).unlockedAtPrestige(7));

        list.add(UpgradeDefinition.of("sugar_rush", 50_000L, UpgradeEffect.comboDuration(1.5)));
        list.add(UpgradeDefinition.of("night_shift", 250_000L, UpgradeEffect.offlineEfficiency(0.25)));
        list.add(UpgradeDefinition.of("kitchen_synergy", 1_000_000L, UpgradeEffect.globalCps(1.1)).requiring("baker", 10));
        list.add(UpgradeDefinition.of("cold_storage", 5_000_000L, UpgradeEffect.offlineEfficiency(0.15)).unlockedAtPrestige(2));
        list.add(UpgradeDefinition.of("rhythm_training", 2_500_000L, UpgradeEffect.comboDuration(1.4)).unlockedAtPrestige(2));
        list.add(UpgradeDefinition.of("silver_polish", 25_000_000L,
                UpgradeEffect.rarityWeight(SpecialCookieRarity.SILVER, 1.5)).unlockedAtPrestige(3));
        list.add(UpgradeDefinition.of("double_glazing", 250_000_000L, UpgradeEffect.goldenValue(2)).unlockedAtPrestige(3));
        list.add(UpgradeDefinition.of("assembly_line", "1e11", UpgradeEffect.globalCps(1.25))
                .requiring("cookie_factory", 25).unlockedAtPrestige(4));
        list.add(UpgradeDefinition.of("quantum_kneading", "1e17", UpgradeEffect.clickAddCpsPercent(2)).unlockedAtPrestige(6));
        list.add(UpgradeDefinition.of("eternal_dough", "1e28", UpgradeEffect.globalCps(1.5)).unlockedAtPrestige(8));

        // Prestige-gated global recipes (see class comment)
        List<PrestigeDefinition> prestiges = prestiges();
        for (int level = 1; level < RECIPE_IDS.length; level++) {
            BigDecimal previousThreshold = prestiges.get(level).requiredLifetimeCookies();
            CookieAmount cost = CookieAmount.of(previousThreshold.divide(RECIPE_COST_DIVISOR));
            list.add(new UpgradeDefinition(RECIPE_IDS[level], "cookie.upgrade." + RECIPE_IDS[level], cost,
                    UpgradeEffect.globalCps(RECIPE_MULTIPLIERS[level]), null, 0, level, null));
        }
        return List.copyOf(list);
    }

    // ------------------------------------------------------------------ prestige

    public static List<PrestigeDefinition> prestiges() {
        List<GeneratorDefinition> gens = generators();
        List<PrestigeDefinition> list = new ArrayList<>();
        list.add(prestige(0, "The First Batch", "0", 1.00, "bakery_square", gens, List.of()));
        list.add(prestige(1, "Sugar Awakening", "1e6", 1.25, "sugar_fields", gens, List.of("sugar_trail")));
        list.add(prestige(2, "Cocoa Frontier", "1e9", 1.60, "cocoa_caverns", gens, List.of("cosmetic_beehive")));
        list.add(prestige(3, "Industrial Baking", "1e12", 2.10, "factory_district", gens, List.of("factory_title")));
        list.add(prestige(4, "Arcane Kitchen", "1e15", 2.80, "arcane_pantry", gens, List.of("arcane_aura")));
        list.add(prestige(5, "Royal Confectionery", "1e18", 3.80, "royal_frosting_keep", gens, List.of("phantom_king_crown")));
        list.add(prestige(6, "Dimensional Dough", "1e22", 5.20, "rift_bakery", gens, List.of("rift_trail")));
        list.add(prestige(7, "Chrono Kitchen", "1e26", 7.20, "chrono_kitchen", gens, List.of("chrono_back_item")));
        list.add(prestige(8, "Stellar Bakery", "1e29", 10.00, "stellar_confectionery", gens, List.of("stellar_aura")));
        list.add(prestige(9, "Reality Baking", "1e33", 14.00, "reality_crust", gens, List.of("end_backpack")));
        list.add(prestige(10, "Cookie Ascendant", "1e36", 20.00, "ascendant_sanctum", gens,
                List.of("cosmetic_dragons_head_ender", "shadow_dragon_wings", "ascendant_title", "ascendant_aura", "ascendant_trail")));
        return List.copyOf(list);
    }

    private static PrestigeDefinition prestige(int level, String name, String threshold, double multiplier, String zone,
                                               List<GeneratorDefinition> gens, List<String> cosmetics) {
        List<String> unlocked = gens.stream().filter(g -> g.unlockPrestige() == level).map(GeneratorDefinition::id).toList();
        return new PrestigeDefinition(level, "cookie.prestige.p" + level, name, new BigDecimal(threshold), multiplier,
                zone, unlocked, cosmetics);
    }

    public static List<PrestigeTreeNode> prestigeTree() {
        return List.of(
                PrestigeTreeNode.of("iron_fingers", 10, 1, PrestigeTreeEffectType.CLICK_POWER_PERCENT, 10),
                PrestigeTreeNode.of("efficient_ovens", 20, 1, PrestigeTreeEffectType.GLOBAL_CPS_PERCENT, 5),
                PrestigeTreeNode.of("night_bakers", 5, 2, PrestigeTreeEffectType.OFFLINE_EFFICIENCY_PERCENT, 10),
                PrestigeTreeNode.of("lucky_charms", 5, 2, PrestigeTreeEffectType.GOLDEN_CHANCE_PERCENT, 10),
                PrestigeTreeNode.of("connoisseur", 5, 5, PrestigeTreeEffectType.RARITY_LUCK_PERCENT, 10),
                PrestigeTreeNode.of("lasting_glow", 5, 2, PrestigeTreeEffectType.GOLDEN_DURATION_PERCENT, 10),
                PrestigeTreeNode.of("sugar_high", 5, 2, PrestigeTreeEffectType.COMBO_DURATION_PERCENT, 10),
                PrestigeTreeNode.of("head_start", 10, 1, PrestigeTreeEffectType.STARTING_COOKIES, 1000),
                new PrestigeTreeNode("helping_hands", "cookie.tree.helping_hands", 10, 1,
                        PrestigeTreeEffectType.STARTING_GENERATORS, 1, "cursor"),
                PrestigeTreeNode.of("steady_hands", 20, 3, PrestigeTreeEffectType.CLICK_POWER_PERCENT, 15),
                PrestigeTreeNode.of("bulk_ovens", 40, 3, PrestigeTreeEffectType.GLOBAL_CPS_PERCENT, 8),
                PrestigeTreeNode.of("golden_hoard", 15, 3, PrestigeTreeEffectType.GOLDEN_CHANCE_PERCENT, 8),
                new PrestigeTreeNode("apprentice_bakers", "cookie.tree.apprentice_bakers", 30, 2,
                        PrestigeTreeEffectType.STARTING_GENERATORS, 5, "baker")
        );
    }

    // ------------------------------------------------------------------ zones

    public static List<ZoneDefinition> zones() {
        return List.of(
                ZoneDefinition.of("bakery_square", 0, 0, "Bakery Square"),
                ZoneDefinition.of("sugar_fields", 1, 1, "Sugar Fields"),
                ZoneDefinition.of("cocoa_caverns", 2, 2, "Cocoa Caverns"),
                ZoneDefinition.of("factory_district", 3, 3, "Factory District"),
                ZoneDefinition.of("arcane_pantry", 4, 4, "Arcane Pantry"),
                ZoneDefinition.of("royal_frosting_keep", 5, 5, "Royal Frosting Keep"),
                ZoneDefinition.of("rift_bakery", 6, 6, "Rift Bakery"),
                ZoneDefinition.of("chrono_kitchen", 7, 7, "Chrono Kitchen"),
                ZoneDefinition.of("stellar_confectionery", 8, 8, "Stellar Confectionery"),
                ZoneDefinition.of("reality_crust", 9, 9, "Reality Crust"),
                ZoneDefinition.of("ascendant_sanctum", 10, 10, "Ascendant Sanctum")
        );
    }

    // ------------------------------------------------------------------ achievements

    public static List<AchievementDefinition> achievements() {
        return List.of(
                AchievementDefinition.of("first_cookie", AchievementCondition.of(Type.LIFETIME_COOKIES, 1)),
                AchievementDefinition.of("clicks_100", AchievementCondition.of(Type.TOTAL_CLICKS, 100)),
                AchievementDefinition.of("clicks_1000", AchievementCondition.of(Type.TOTAL_CLICKS, 1_000)),
                AchievementDefinition.of("clicks_10000", AchievementCondition.of(Type.TOTAL_CLICKS, 10_000)),
                AchievementDefinition.of("lifetime_1m", AchievementCondition.of(Type.LIFETIME_COOKIES, "1e6")),
                AchievementDefinition.of("lifetime_1b", AchievementCondition.of(Type.LIFETIME_COOKIES, "1e9")),
                AchievementDefinition.of("first_generator", AchievementCondition.of(Type.TOTAL_GENERATORS, 1)),
                AchievementDefinition.of("generators_100", AchievementCondition.of(Type.TOTAL_GENERATORS, 100)),
                AchievementDefinition.of("generators_500", AchievementCondition.of(Type.TOTAL_GENERATORS, 500)),
                AchievementDefinition.of("first_golden", AchievementCondition.of(Type.GOLDEN_COOKIES_CLICKED, 1)),
                AchievementDefinition.of("golden_100", AchievementCondition.of(Type.GOLDEN_COOKIES_CLICKED, 100)),
                AchievementDefinition.of("first_prestige", AchievementCondition.of(Type.PRESTIGE_LEVEL, 1)),
                AchievementDefinition.of("prestige_5", AchievementCondition.of(Type.PRESTIGE_LEVEL, 5)),
                AchievementDefinition.of("prestige_10", AchievementCondition.of(Type.PRESTIGE_LEVEL, 10)),
                AchievementDefinition.of("clicks_100000", AchievementCondition.of(Type.TOTAL_CLICKS, 100_000)),
                AchievementDefinition.of("lifetime_1t", AchievementCondition.of(Type.LIFETIME_COOKIES, "1e12")),
                AchievementDefinition.of("lifetime_1qa", AchievementCondition.of(Type.LIFETIME_COOKIES, "1e15")),
                AchievementDefinition.of("generators_1000", AchievementCondition.of(Type.TOTAL_GENERATORS, 1_000)),
                AchievementDefinition.of("golden_10", AchievementCondition.of(Type.GOLDEN_COOKIES_CLICKED, 10)),
                AchievementDefinition.of("golden_500", AchievementCondition.of(Type.GOLDEN_COOKIES_CLICKED, 500)),
                AchievementDefinition.of("prestige_3", AchievementCondition.of(Type.PRESTIGE_LEVEL, 3)),
                AchievementDefinition.of("prestige_7", AchievementCondition.of(Type.PRESTIGE_LEVEL, 7)),
                AchievementDefinition.of("all_zones_discovered", AchievementCondition.allZonesDiscovered())
        );
    }

    /** Convenience: all default recipe ids by unlock level (level 1..9). */
    public static Map<Integer, String> recipeIdsByLevel() {
        Map<Integer, String> map = new java.util.LinkedHashMap<>();
        for (int i = 1; i < RECIPE_IDS.length; i++) map.put(i, RECIPE_IDS[i]);
        return Map.copyOf(map);
    }
}
