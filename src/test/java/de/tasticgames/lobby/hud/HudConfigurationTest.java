package de.tasticgames.lobby.hud;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("cuboide:iconic_crown", configuration.iconId("rank"));
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
    void fontWidthsFollowTheDefaultFont() {
        assertEquals(0, FontWidths.width(""));
        assertEquals(6 * 5, FontWidths.width("Hello".replace("l", "H"))); // 5 regular glyphs
        assertEquals(6 + 6 + 3 + 3 + 6, FontWidths.width("Hello"));
        assertEquals(2, FontWidths.width(":"));
        assertTrue(FontWidths.width("Prestige: 10") > FontWidths.width("CPS: 1"));
    }
}
