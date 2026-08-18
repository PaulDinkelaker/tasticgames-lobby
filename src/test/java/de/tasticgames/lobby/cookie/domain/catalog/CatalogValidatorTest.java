package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogValidatorTest {

    private static CookieCatalog build(List<GeneratorDefinition> gens, List<UpgradeDefinition> ups,
                                       List<PrestigeDefinition> prestiges, List<PrestigeTreeNode> tree,
                                       List<ZoneDefinition> zones, List<AchievementDefinition> achievements) {
        return new CookieCatalog(gens, ups, prestiges, tree, zones, achievements);
    }

    @Test
    void defaultsAreValidAndComplete() {
        CookieCatalog c = CookieCatalog.defaults();
        assertEquals(11, c.generators().size());
        assertEquals(11, c.prestiges().size());
        assertEquals(11, c.zones().size());
        assertEquals(13, c.prestigeTree().size());
        assertEquals(23, c.achievements().size());
        assertTrue(c.upgrades().size() >= 25, "expected at least 25 upgrades, got " + c.upgrades().size());
        assertEquals(10, c.maxPrestigeLevel());
        assertEquals("sugar_farm", c.requirePrestige(1).unlockedGeneratorIds().getFirst());
        assertEquals(List.of("portal_bakery"), c.requirePrestige(6).unlockedGeneratorIds());
        assertEquals(List.of(), c.requirePrestige(5).unlockedGeneratorIds());
        assertEquals(5, c.requirePrestige(10).rewardCosmeticIds().size());
        assertEquals(new BigDecimal("1e36"), c.requirePrestige(10).requiredLifetimeCookies());
        assertEquals(20.0, c.requirePrestige(10).totalMultiplier());
        assertEquals("ascendant_sanctum", c.requirePrestige(10).unlockedZoneId());
        Set<String> zoneIds = c.zones().stream().map(ZoneDefinition::id).collect(Collectors.toSet());
        for (PrestigeDefinition p : c.prestiges()) assertTrue(zoneIds.contains(p.unlockedZoneId()));
    }

    @Test
    void generatorTableMatchesSpecification() {
        CookieCatalog c = CookieCatalog.defaults();
        assertEquals(CookieAmount.of(15), c.requireGenerator("cursor").baseCost());
        assertEquals(CookieAmount.of(1_200_000_000_000L), c.requireGenerator("reality_forge").baseCost());
        assertEquals(new BigDecimal("10000000"), c.requireGenerator("reality_forge").baseCps());
        assertEquals(9, c.requireGenerator("reality_forge").unlockPrestige());
        assertEquals(List.of(10, 25, 50, 100, 200), c.requireGenerator("oven").milestoneCounts());
        assertEquals(2.0, c.requireGenerator("oven").milestoneMultiplier());
    }

    @Test
    void duplicateGeneratorIdIsRejected() {
        List<GeneratorDefinition> gens = new ArrayList<>(DefaultCatalog.generators());
        gens.add(GeneratorDefinition.of("cursor", 99, 1, "1", 0));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(gens, DefaultCatalog.upgrades(), DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("Duplicate generator id: cursor"), ex.getMessage());
    }

    @Test
    void duplicateUpgradeIdIsRejected() {
        List<UpgradeDefinition> ups = new ArrayList<>(DefaultCatalog.upgrades());
        ups.add(ups.getFirst());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), ups, DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("Duplicate upgrade id"), ex.getMessage());
    }

    @Test
    void decreasingPrestigeThresholdIsRejected() {
        List<PrestigeDefinition> prestiges = new ArrayList<>(DefaultCatalog.prestiges());
        PrestigeDefinition p3 = prestiges.get(3);
        prestiges.set(3, new PrestigeDefinition(3, p3.nameKey(), p3.displayName(), new BigDecimal("1e8"), p3.totalMultiplier(),
                p3.unlockedZoneId(), p3.unlockedGeneratorIds(), p3.rewardCosmeticIds()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), DefaultCatalog.upgrades(), prestiges, DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("strictly increasing"), ex.getMessage());
    }

    @Test
    void decreasingPrestigeMultiplierIsRejected() {
        List<PrestigeDefinition> prestiges = new ArrayList<>(DefaultCatalog.prestiges());
        PrestigeDefinition p5 = prestiges.get(5);
        prestiges.set(5, new PrestigeDefinition(5, p5.nameKey(), p5.displayName(), p5.requiredLifetimeCookies(), 1.0,
                p5.unlockedZoneId(), p5.unlockedGeneratorIds(), p5.rewardCosmeticIds()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), DefaultCatalog.upgrades(), prestiges, DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("non-decreasing"), ex.getMessage());
    }

    @Test
    void unsortedMilestonesAreRejected() {
        List<GeneratorDefinition> gens = new ArrayList<>(DefaultCatalog.generators());
        gens.set(0, new GeneratorDefinition("cursor", 0, CookieAmount.of(15), new BigDecimal("0.1"), 0, List.of(10, 5), 2.0));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(gens, DefaultCatalog.upgrades(), DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("strictly ascending"), ex.getMessage());
    }

    @Test
    void nonPositiveCostOrCpsIsRejected() {
        List<GeneratorDefinition> gens = new ArrayList<>(DefaultCatalog.generators());
        gens.set(0, new GeneratorDefinition("cursor", 0, CookieAmount.of(15), BigDecimal.ZERO, 0, List.of(10), 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                build(gens, DefaultCatalog.upgrades(), DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        List<UpgradeDefinition> ups = new ArrayList<>(DefaultCatalog.upgrades());
        ups.add(UpgradeDefinition.of("free_stuff", 0L, UpgradeEffect.clickMultiplier(2)));
        assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), ups, DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
    }

    @Test
    void upgradeReferencingUnknownGeneratorIsRejected() {
        List<UpgradeDefinition> ups = new ArrayList<>(DefaultCatalog.upgrades());
        ups.add(UpgradeDefinition.of("ghost_boost", 10L, UpgradeEffect.generatorMultiplier("ghost", 2)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), ups, DefaultCatalog.prestiges(), DefaultCatalog.prestigeTree(),
                        DefaultCatalog.zones(), DefaultCatalog.achievements()));
        assertTrue(ex.getMessage().contains("unknown generator ghost"), ex.getMessage());
    }

    @Test
    void invalidIdFormatIsRejected() {
        List<ZoneDefinition> zones = new ArrayList<>(DefaultCatalog.zones());
        zones.add(ZoneDefinition.of("Bad Zone", 99, 0, "Bad"));
        assertThrows(IllegalArgumentException.class, () ->
                build(DefaultCatalog.generators(), DefaultCatalog.upgrades(), DefaultCatalog.prestiges(),
                        DefaultCatalog.prestigeTree(), zones, DefaultCatalog.achievements()));
    }

    @Test
    void lookupsWork() {
        CookieCatalog c = CookieCatalog.defaults();
        assertTrue(c.generator("nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.requireGenerator("nope"));
        assertEquals(4, c.upgradesForGenerator("oven").size(), "four tiers per generator");
        assertTrue(c.treeNode("iron_fingers").isPresent());
        assertTrue(c.treeNode("connoisseur").isPresent());
        assertTrue(c.upgrade("master_recipe_book").isPresent());
        assertTrue(c.zone("rift_bakery").isPresent());
        assertTrue(c.achievement("first_cookie").isPresent());
    }
}
