package de.tasticgames.lobby.hud.pack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die beiden Formate, in denen UI-Icon-Packs geliefert werden – und das Beiwerk, das nicht auf den
 * Server gehört.
 */
class IconPackLayoutTest {

    @Test
    void einFertigesItemsAdderPackBenutztNurSeineFontTexturen() {
        IconPackLayout layout = IconPackLayout.detect(List.of(
                "90-ui-icons-items-pack/README.md",
                "90-ui-icons-items-pack/itemsadder/contents/narra_icons_items/narra_icons_items.yml",
                "90-ui-icons-items-pack/itemsadder/contents/narra_icons_items/textures/font/home.png",
                "90-ui-icons-items-pack/itemsadder/contents/narra_icons_items/textures/font/gold_star.png",
                "90-ui-icons-items-pack/icons/navigation/home.png",
                "90-ui-icons-items-pack/icons_312/home.png",
                "90-ui-icons-items-pack/previews/sheet.png",
                "90-ui-icons-items-pack/source/icons.aseprite"));

        assertEquals(java.util.Set.of("home", "gold_star"), layout.icons().keySet());
        assertTrue(layout.icons().get("home").endsWith("textures/font/home.png"),
                "die freigegebenen Texturen gewinnen gegen die losen Ordner");
        assertEquals("90-ui-icons-items-pack/itemsadder/contents/narra_icons_items/narra_icons_items.yml",
                layout.configCandidates().getFirst());
        assertFalse(layout.isEmpty());
    }

    @Test
    void einHerstellerpackMitLosenIconsWirdErkannt() {
        IconPackLayout layout = IconPackLayout.detect(List.of(
                "readme.txt",
                "configs/ItemsAdder/icons.yml",
                "configs/oraxen/icons.yml",
                "configs/deluxemenus/icons.yml",
                "icons/search.png",
                "icons/settings.png"));

        assertEquals(2, layout.icons().size());
        assertEquals("icons/search.png", layout.icons().get("search"));
        assertEquals("configs/ItemsAdder/icons.yml", layout.configCandidates().getFirst(),
                "die ItemsAdder-Fassung steht vor der von Oraxen und DeluxeMenus");
    }

    @Test
    void beiwerkWirdNieInstalliert() {
        IconPackLayout layout = IconPackLayout.detect(List.of(
                "pack/configs/ItemsAdder/icons.yml",
                "pack/icons/ok.png",
                "pack/icons/needs_review/broken.png",
                "pack/previews/overview.png",
                "pack/icons_312/ok.png",
                "__MACOSX/._icons.png"));

        assertEquals(List.of("ok"), List.copyOf(layout.icons().keySet()));
    }

    @Test
    void ohneIconsOderKonfigurationIstNichtsZuTun() {
        assertTrue(IconPackLayout.detect(List.of("readme.txt")).isEmpty());
        assertTrue(IconPackLayout.detect(List.of("icons/a.png")).isEmpty(), "ohne Konfiguration fehlt der Namensraum");
        assertTrue(IconPackLayout.detect(List.of("configs/icons.yml")).isEmpty(), "ohne Bilder gibt es nichts zu zeigen");
    }

    @Test
    void derKuerzestePfadGewinntBeiGleichemNamen() {
        IconPackLayout layout = IconPackLayout.detect(List.of(
                "pack/configs/ItemsAdder/icons.yml",
                "pack/icons/deep/nested/home.png",
                "pack/icons/home.png"));

        assertEquals("pack/icons/home.png", layout.icons().get("home"));
    }
}
