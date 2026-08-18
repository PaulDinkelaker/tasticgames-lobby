package de.tasticgames.lobby.hud;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudConfigurationTest {

    @Test
    void bundledHudConfigurationLoads() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/hud.yml"), StandardCharsets.UTF_8));
        HudConfiguration configuration = HudConfiguration.load(yaml);
        assertTrue(configuration.enabled());
        assertEquals(10, configuration.refreshTicks());
        assertEquals(BossBar.Color.PINK, configuration.bossbarColor());
        assertEquals("tasticgames:hud_box_mid", configuration.boxMiddle());
        assertEquals("moon_ranks:moon_rank_blue_moon_1", configuration.iconId("rank"));
        assertEquals(0, configuration.glyphSpacing(), "ItemsAdder font images advance by their reported width");
        assertEquals(6, configuration.boxPadding());
        assertEquals(6, configuration.boxGap());
        assertTrue(configuration.iaAutoZip());
        assertFalse(configuration.unicodeIcon("cookie").isEmpty());
        assertEquals(4, configuration.valueColors().size());
        assertEquals(configuration.valueColors().get(0), configuration.valueColor(5)); // wraps around
        for (String key : new String[]{"lobby", "cookie", "social", "gateway", "profile", "cosmetics", "settings", "visibility",
                "cookies", "cps", "prestige", "generators", "friends", "party", "clan", "requests", "survival", "modes",
                "rank", "playtime", "kills", "deaths", "hat", "aura", "trail", "title", "language", "music", "sounds", "online"}) {
            assertFalse(configuration.iconId(key).isBlank(), "icon mapping missing for " + key);
            assertFalse(configuration.unicodeIcon(key).isBlank(), "unicode fallback missing for " + key);
        }
    }

    @Test
    void invalidBossbarColorFailsFast() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("itemsadder.bossbar-color", "RAINBOW");
        assertThrows(IllegalArgumentException.class, () -> HudConfiguration.load(yaml));
    }

    @Test
    void pixelOffsetsComposeExactAdvances() {
        assertEquals("", PixelOffsets.chars(0));
        assertEquals(1, PixelOffsets.chars(-1).length());
        assertEquals(2, PixelOffsets.chars(-3).length());   // -2 and -1
        assertEquals(3, PixelOffsets.chars(7).length());    // 4 + 2 + 1
        assertEquals(1, PixelOffsets.chars(-256).length());
        assertEquals(2, PixelOffsets.chars(512).length());  // 256 + 256
        // negative and positive glyphs never overlap
        assertNotEquals(PixelOffsets.chars(4), PixelOffsets.chars(-4));
        // every glyph an offset uses must be declared in the exported font
        String json = PixelOffsets.fontJson();
        assertTrue(json.contains("\"type\": \"space\""), json);
        for (int pixels : new int[]{-511, -1, 1, 511}) {
            for (char glyph : PixelOffsets.chars(pixels).toCharArray()) {
                assertTrue(json.contains(String.format("\\u%04X", (int) glyph)), "font misses the glyph for " + pixels);
            }
        }
        assertTrue(json.contains(": -256"), json);
        assertTrue(json.contains(": 1"), json);
    }

    @Test
    void fontWidthsFollowTheDefaultFont() {
        assertEquals(0, FontWidths.width(""));
        assertEquals(6 * 5, FontWidths.width("Hello".replace("l", "H"))); // 5 regular glyphs
        assertEquals(6 + 6 + 3 + 3 + 6, FontWidths.width("Hello"));
        assertEquals(2, FontWidths.width(":"));
        assertEquals(2, FontWidths.width("·"), "middle dot is a 1 px glyph + spacing");
        assertEquals(6 + 4 + 2 + 4 + 6, FontWidths.width("a · a"));
        assertTrue(FontWidths.width("Prestige: 10") > FontWidths.width("CPS: 1"));
    }
}
