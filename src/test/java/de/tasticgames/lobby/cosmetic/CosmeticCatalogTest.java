package de.tasticgames.lobby.cosmetic;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticCatalogTest {

    @Test
    void defaultCatalogLoadsAndCoversPrestigeRewards() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cosmetics.yml"), StandardCharsets.UTF_8));
        CosmeticCatalog catalog = CosmeticCatalog.load(yaml);
        assertTrue(catalog.size() >= 50, "the asset packs contribute the bulk of the catalog");
        // every cosmetic a prestige level hands out must exist
        for (String id : new String[]{"sugar_trail", "cosmetic_beehive", "factory_title", "arcane_aura", "phantom_king_crown",
                "rift_trail", "chrono_back_item", "stellar_aura", "end_backpack", "cosmetic_dragons_head_ender",
                "shadow_dragon_wings", "ascendant_title", "ascendant_aura", "ascendant_trail"}) {
            assertTrue(catalog.find(id).isPresent(), "prestige reward cosmetic missing: " + id);
        }
        assertEquals(CosmeticCategory.TRAIL, catalog.find("sugar_trail").orElseThrow().category());
        assertTrue(catalog.byCategory(CosmeticCategory.AURA).size() >= 3);
        // hats, back items and gadgets come from the installed asset packs and are rendered by HMCCosmetics
        for (CosmeticCategory category : new CosmeticCategory[]{CosmeticCategory.HAT, CosmeticCategory.BACK_ITEM, CosmeticCategory.LOBBY_GADGET}) {
            assertTrue(catalog.byCategory(category).size() >= 5, "pack cosmetics missing for " + category);
        }
        assertTrue(catalog.find("cosmetic_dragons_head_ender").orElseThrow().renderData().startsWith("hmc:"));
    }

    @Test
    void duplicateOrInvalidEntriesFailFast() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("cosmetics.a.category", "HAT");
        yaml.set("cosmetics.a.rarity", "COMMON");
        yaml.set("cosmetics.a.preview", "NOT_A_MATERIAL");
        assertThrows(IllegalArgumentException.class, () -> CosmeticCatalog.load(yaml));
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("cosmetics.a.category", "SPACESHIP");
        assertThrows(IllegalArgumentException.class, () -> CosmeticCatalog.load(bad));
    }

    @Test
    void visibilityModesCycle() {
        de.tasticgames.lobby.visibility.VisibilityMode mode = de.tasticgames.lobby.visibility.VisibilityMode.ALL;
        for (int i = 0; i < de.tasticgames.lobby.visibility.VisibilityMode.values().length; i++) {
            mode = mode.next();
        }
        assertEquals(de.tasticgames.lobby.visibility.VisibilityMode.ALL, mode);
        assertEquals(java.util.Optional.of(de.tasticgames.lobby.visibility.VisibilityMode.FRIENDS_AND_PARTY),
                de.tasticgames.lobby.visibility.VisibilityMode.find("friends_and_party"));
    }
}
