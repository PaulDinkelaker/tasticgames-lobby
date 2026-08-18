package de.tasticgames.lobby.cosmetic.pack;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archive layouts of the cosmetic packs the server actually uses. */
class CosmeticPackTargetTest {

    @Test
    void itemsAdderContentKeepsItsPackFolder() {
        CosmeticPackTarget target = CosmeticPackTarget.of(
                "ItemsAdder/contents/samus_cosmetics_1/configs/samus_cosmetics_1/items.yml").orElseThrow();
        assertEquals("ItemsAdder", target.plugin());
        assertEquals("contents/samus_cosmetics_1/configs/samus_cosmetics_1/items.yml", target.relativePath());
        assertEquals("samus_cosmetics_1", target.contentPack());
        assertFalse(target.protectedFile());

        // the packs ship their ItemsAdder flavour below a prose folder name
        assertEquals("contents/cosmetic_expansion_v1/configs/cosmetic_expansion_v1.yml",
                CosmeticPackTarget.of("IA Configs/ItemsAdder/contents/cosmetic_expansion_v1/configs/cosmetic_expansion_v1.yml")
                        .orElseThrow().relativePath());
        assertEquals("contents/clones_armors/configs/armor/501st_armor.yml",
                CosmeticPackTarget.of("ItemsAdder Setup/contents/clones_armors/configs/armor/501st_armor.yml")
                        .orElseThrow().relativePath());
    }

    @Test
    void theOlderResourcePackSpellingIsNormalised() {
        CosmeticPackTarget target = CosmeticPackTarget.of(
                "itemsadder\\darksoul\\resource_pack\\assets\\darksoul\\models\\darksoul\\soul_axe.json").orElseThrow();
        assertEquals("ItemsAdder", target.plugin());
        assertEquals("contents/darksoul/resourcepack/assets/darksoul/models/darksoul/soul_axe.json", target.relativePath());
    }

    @Test
    void hmcCosmeticsDefinitionsAreInstalledAndMenusAreProtected() {
        CosmeticPackTarget cosmetics = CosmeticPackTarget.of("HMCCosmetics/cosmetics/samus_cosmetics_1.yml").orElseThrow();
        assertEquals("HMCCosmetics", cosmetics.plugin());
        assertEquals("cosmetics/samus_cosmetics_1.yml", cosmetics.relativePath());
        assertFalse(cosmetics.protectedFile());

        CosmeticPackTarget menu = CosmeticPackTarget.of("IA Configs/HMCCosmetics/menus/defaultmenu.yml").orElseThrow();
        assertEquals("menus/defaultmenu.yml", menu.relativePath());
        assertTrue(menu.protectedFile(), "an existing menu must never be overwritten");
    }

    @Test
    void modelEngineBlueprintsAreInstalled() {
        CosmeticPackTarget target = CosmeticPackTarget.of("ModelEngine/blueprints/robot_01.bbmodel").orElseThrow();
        assertEquals("ModelEngine", target.plugin());
        assertEquals("blueprints/robot_01.bbmodel", target.relativePath());
    }

    @Test
    void thePreThreeItemsAdderLayoutBecomesAContentPack() {
        // Jeqo icon packs ship plugins/ItemsAdder/data/... - the layout ItemsAdder used before 3.x
        CosmeticPackTarget config = CosmeticPackTarget.of(
                "Jeqo - Icon Pack 2/Plugins/ItemsAdder/data/items_packs/jeqo/icon_pack_2.yml").orElseThrow();
        assertEquals("ItemsAdder", config.plugin());
        assertEquals("contents/jeqo/configs/icon_pack_2.yml", config.relativePath());
        assertEquals("jeqo", config.contentPack());

        CosmeticPackTarget model = CosmeticPackTarget.of(
                "Plugins/ItemsAdder/data/resource_pack/assets/jeqo/models/icons/coin.json").orElseThrow();
        assertEquals("contents/jeqo/resourcepack/assets/jeqo/models/icons/coin.json", model.relativePath());
    }

    @Test
    void aContentPackInTheArchiveRootIsInstalledAsWell() {
        CosmeticPackTarget target = CosmeticPackTarget.of("sunmoon_wizard/configs/1.yml").orElseThrow();
        assertEquals("ItemsAdder", target.plugin());
        assertEquals("contents/sunmoon_wizard/configs/1.yml", target.relativePath());
        assertEquals("sunmoon_wizard", target.contentPack());
    }

    @Test
    void mythicMobsPacksAreInstalled() {
        CosmeticPackTarget pack = CosmeticPackTarget.of("MythicMobs/Packs/pets_robot/Mobs/Mobs.yml").orElseThrow();
        assertEquals("MythicMobs", pack.plugin());
        assertEquals("Packs/pets_robot/Mobs/Mobs.yml", pack.relativePath());

        // a .bbmodel outside ModelEngine/blueprints stays untouched: those are the artist's source files
        assertTrue(CosmeticPackTarget.of("32x Armor/deadpool_armor.bbmodel").isEmpty());
    }

    @Test
    void foreignAndLegacyVariantsAreIgnored() {
        for (String entry : new String[]{
                "Oraxen Configs/HMCCosmetics/cosmetics/cosmetics_expansion_v1.yml",
                "Configs/Nexo Configs/ItemsAdder/contents/x/configs/x.yml",
                "MagicCosmetics 1.20.2 Setup or higher version/MagicCosmetics/cosmetics/necros.yml",
                "CosmeticsCore/cosmetics/samus_cosmetics_1.yml",
                "HMCCosmetics 1.20.1 or lower Setup/HMCCosmetics/cosmetics/necros_set.yml",
                "Default Resource Pack Datapack 1.20+ Setup/pack.mcmeta",
                "README.txt",
                "bbmodels/chicken_hat.bbmodel",
                "Marketing Art/showcase.png"}) {
            assertEquals(Optional.empty(), CosmeticPackTarget.of(entry), entry);
        }
        assertEquals(Optional.empty(), CosmeticPackTarget.of("ItemsAdder/contents/pack/"), "directories are skipped");
        assertEquals(Optional.empty(), CosmeticPackTarget.of(null));
    }

    @Test
    void currentVariantsOfTheSamePackAreStillInstalled() {
        assertTrue(CosmeticPackTarget.of(
                "HMCCosmetics 1.20.2 Setup or higher version/HMCCosmetics/cosmetics/necros_set.yml").isPresent());
    }
}
