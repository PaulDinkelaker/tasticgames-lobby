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
