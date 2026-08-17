package de.tasticgames.lobby.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudAssetExporterTest {

    private static final Logger LOGGER = Logger.getLogger("HudAssetExporterTest");

    @Test
    void exportWritesContentOnceAndTracksThePackState(@TempDir Path temp) throws Exception {
        File itemsAdder = temp.toFile();
        HudAssetExporter exporter = new HudAssetExporter(itemsAdder, LOGGER);

        HudAssetExporter.Result first = exporter.export("PINK");
        assertFalse(first.written().isEmpty());
        assertTrue(first.packPending(), "fresh export: the pack does not contain the files yet");
        File contents = new File(itemsAdder, "contents/tasticgames");
        assertTrue(new File(contents, "configs/tasticgames_hud.yml").isFile());
        assertTrue(new File(contents, "textures/hud/box_mid.png").isFile(), "3.x texture layout");
        assertTrue(new File(contents, "resourcepack/assets/tasticgames/textures/hud/box_mid.png").isFile(), "4.x texture layout");
        assertTrue(new File(contents, "resourcepack/assets/tasticgames/font/space.json").isFile());
        assertTrue(new File(contents, "resourcepack/assets/tasticgames/items/profile_head.json").isFile());
        assertTrue(new File(contents, "resourcepack/assets/tasticgames/models/item/profile_head.json").isFile());
        assertTrue(new File(contents, "resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/pink_background.png").isFile());
        String marker = Files.readString(new File(contents, ".tasticlobby-assets").toPath(), StandardCharsets.UTF_8);
        assertEquals(HudAssetExporter.ASSET_VERSION, HudAssetExporter.versionOf(marker));
        assertFalse(marker.contains("zipped"));

        // restart before /iazip ran: nothing to write, but the pack is still pending
        HudAssetExporter.Result second = new HudAssetExporter(itemsAdder, LOGGER).export("PINK");
        assertTrue(second.written().isEmpty());
        assertTrue(second.packPending());

        // pack regenerated: remembered across restarts
        exporter.markPackCurrent();
        HudAssetExporter.Result third = new HudAssetExporter(itemsAdder, LOGGER).export("PINK");
        assertTrue(third.written().isEmpty());
        assertFalse(third.packPending());

        // older asset version on disk: our files are refreshed, foreign files untouched
        File foreign = new File(contents, "configs/my_own_items.yml");
        Files.writeString(foreign.toPath(), "items: {}", StandardCharsets.UTF_8);
        Files.writeString(new File(contents, ".tasticlobby-assets").toPath(), "1 zipped", StandardCharsets.UTF_8);
        HudAssetExporter.Result fourth = new HudAssetExporter(itemsAdder, LOGGER).export("PINK");
        assertFalse(fourth.written().isEmpty());
        assertTrue(fourth.packPending());
        assertEquals("items: {}", Files.readString(foreign.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void profileHeadModelIsValidJsonWithFrontFacingGuiTransform() {
        JsonObject definition = JsonParser.parseString(HudAssetExporter.profileHeadDefinition()).getAsJsonObject();
        JsonObject model = definition.getAsJsonObject("model");
        assertEquals("minecraft:special", model.get("type").getAsString());
        assertEquals("tasticgames:item/profile_head", model.get("base").getAsString());
        assertEquals("minecraft:head", model.getAsJsonObject("model").get("type").getAsString());
        assertEquals("player", model.getAsJsonObject("model").get("kind").getAsString());

        JsonObject transforms = JsonParser.parseString(HudAssetExporter.profileHeadModel()).getAsJsonObject();
        assertEquals("minecraft:item/template_skull", transforms.get("parent").getAsString());
        JsonObject gui = transforms.getAsJsonObject("display").getAsJsonObject("gui");
        assertEquals(0, gui.getAsJsonArray("rotation").get(1).getAsInt());
        double scale = gui.getAsJsonArray("scale").get(0).getAsDouble();
        assertTrue(scale > 1.0 && scale <= 4.0, "display scale must stay within Minecraft's limits");
        assertEquals(4 * scale, gui.getAsJsonArray("translation").get(1).getAsDouble(), 1e-6, "head centre offset compensated");
        assertEquals("tasticgames:profile_head", HudAssetExporter.PROFILE_HEAD_MODEL);
    }

    @Test
    void boxGlyphsHaveTheDeclaredPixelWidths() {
        assertEquals(HudAssetExporter.CAP_WIDTH, HudAssetExporter.box(HudAssetExporter.CAP_WIDTH, true, false).getWidth());
        assertEquals(HudAssetExporter.MID_WIDTH, HudAssetExporter.box(HudAssetExporter.MID_WIDTH, false, false).getWidth());
        assertEquals(HudAssetExporter.BOX_HEIGHT, HudAssetExporter.box(HudAssetExporter.MID_WIDTH, false, false).getHeight());
        // the rightmost column of every glyph is opaque somewhere (Minecraft measures the advance from it)
        var right = HudAssetExporter.box(HudAssetExporter.CAP_WIDTH, false, true);
        boolean opaque = false;
        for (int y = 0; y < right.getHeight(); y++) {
            opaque |= (right.getRGB(right.getWidth() - 1, y) >>> 24) != 0;
        }
        assertTrue(opaque);
        assertEquals(0, HudAssetExporter.versionOf(""));
        assertEquals(2, HudAssetExporter.versionOf("2"));
        assertEquals(3, HudAssetExporter.versionOf("3 zipped\n"));
    }
}
