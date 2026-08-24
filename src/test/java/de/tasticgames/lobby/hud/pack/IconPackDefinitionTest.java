package de.tasticgames.lobby.hud.pack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Beide Herstellerfassungen werden auf dieselbe ItemsAdder-Konfiguration gebracht. */
class IconPackDefinitionTest {

    private static Optional<IconPackDefinition> parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(new StringReader(yaml));
        return IconPackDefinition.parse(configuration);
    }

    @Test
    void eineEchteItemsAdderKonfigurationWirdUebernommen() throws Exception {
        IconPackDefinition definition = parse("""
                info:
                  namespace: narra_icons_items
                font_images:
                  home:
                    path: font/home.png
                    scale_ratio: 9
                    y_position: 8
                  gold_star:
                    path: font/gold_star.png
                    scale_ratio: 11
                    y_position: 7
                """).orElseThrow();

        assertEquals("narra_icons_items", definition.namespace());
        assertEquals(2, definition.entries().size());
        assertEquals(11, definition.entries().get("gold_star").scaleRatio());
        assertEquals(7, definition.entries().get("gold_star").yPosition());
        assertEquals("font/gold_star.png", definition.entries().get("gold_star").texture());
        assertEquals("gold_star.png", definition.entries().get("gold_star").sourceName());
    }

    @Test
    void dieVereinfachteHerstellerfassungWirdUebersetzt() throws Exception {
        IconPackDefinition definition = parse("""
                info:
                  namespace: minimalui
                textures:
                  search:
                    file: icons/search.png
                  settings:
                    file: icons/settings.png
                """).orElseThrow();

        assertEquals("minimalui", definition.namespace());
        assertEquals("font/search.png", definition.entries().get("search").texture(),
                "die Textur landet immer unter font/<id>.png, egal wie sie im Pack hieß");
        assertEquals("search.png", definition.entries().get("search").sourceName());
        assertEquals(IconPackDefinition.DEFAULT_SCALE_RATIO, definition.entries().get("search").scaleRatio());
        assertEquals(IconPackDefinition.DEFAULT_Y_POSITION, definition.entries().get("settings").yPosition());
    }

    @Test
    void dieGeschriebeneKonfigurationIstFuerItemsAdderLesbar() throws Exception {
        IconPackDefinition definition = parse("""
                info:
                  namespace: MinimalUI
                textures:
                  search:
                    file: icons/search.png
                """).orElseThrow();

        String yaml = definition.toItemsAdderConfig();
        assertTrue(yaml.contains("namespace: minimalui"), "Namensräume sind klein geschrieben: " + yaml);
        assertTrue(yaml.contains("font_images:"));
        assertTrue(yaml.contains("  search:"));
        assertTrue(yaml.contains("    path: font/search.png"));
        assertTrue(yaml.contains("    scale_ratio: 9"));
    }

    @Test
    void ohneNamensraumOderEintraegeGibtEsKeinPack() throws Exception {
        assertTrue(parse("textures:\n  search:\n    file: icons/search.png\n").isEmpty());
        assertTrue(parse("info:\n  namespace: minimalui\n").isEmpty());
    }
}
