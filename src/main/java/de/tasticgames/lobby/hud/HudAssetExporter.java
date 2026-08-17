package de.tasticgames.lobby.hud;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Exports the ItemsAdder content TasticLobby needs into {@code plugins/ItemsAdder/contents/tasticgames}:
 * dark rounded HUD box glyphs, the pixel-offset font, transparent boss bar textures, the flat 2D
 * profile icon item and the front-facing player-head model ({@link #PROFILE_HEAD_MODEL}). Files are
 * versioned ({@link #ASSET_VERSION}): our own files are rewritten when the version marker is older,
 * foreign files are never touched. The marker also remembers whether the ItemsAdder pack was
 * regenerated ({@code /iazip}) after the last export ({@link Result#packPending()}), so a restart in
 * between does not leave the pack stale.
 */
public final class HudAssetExporter {

    /** Bump when generated textures/configs change so existing installations pick them up. */
    public static final int ASSET_VERSION = 3;
    public static final int BOX_HEIGHT = 16;
    public static final int BOX_Y_POSITION = 12;
    public static final int CAP_WIDTH = 4;
    public static final int MID_WIDTH = 8;
    public static final String PROFILE_ITEM = "tasticgames:profile_icon";
    /** Vanilla item model (item_model component) rendering the player's own head front-facing, filling the slot. */
    public static final String PROFILE_HEAD_MODEL = PixelOffsets.NAMESPACE + ":profile_head";
    /** GUI scale/offset of the head model: 8.5 units (head + hat layer) × 1.75 ≈ 15 px, centred (head centre sits 4 units below block centre). */
    static final double PROFILE_HEAD_GUI_SCALE = 1.75;
    static final double PROFILE_HEAD_GUI_TRANSLATION_Y = 4 * PROFILE_HEAD_GUI_SCALE;

    private static final String MARKER_ZIPPED = "zipped";

    /**
     * @param written     files created or updated by this export (empty when everything is current)
     * @param packPending true when the ItemsAdder pack does not contain the current files yet (export wrote
     *                    files now, or an earlier export was never followed by {@code /iazip})
     */
    public record Result(List<File> written, boolean packPending) {
        public Result {
            written = List.copyOf(written);
        }
    }

    private final File itemsAdderFolder;
    private final Logger logger;

    public HudAssetExporter(File itemsAdderFolder, Logger logger) {
        this.itemsAdderFolder = Objects.requireNonNull(itemsAdderFolder);
        this.logger = Objects.requireNonNull(logger);
    }

    private File contents() {
        return new File(itemsAdderFolder, "contents" + File.separator + "tasticgames");
    }

    private File marker() {
        return new File(contents(), ".tasticlobby-assets");
    }

    public Result export(String bossbarColor) throws IOException {
        File contents = contents();
        File configs = new File(contents, "configs");
        // ItemsAdder 3.x resolves texture paths below contents/<pack>/textures/, 4.x below resourcepack/assets/<ns>/textures/ – write both
        File texturesLegacy = new File(contents, "textures");
        File assets = new File(contents, "resourcepack" + File.separator + "assets");
        File namespace = new File(assets, PixelOffsets.NAMESPACE);
        File textures = new File(namespace, "textures");
        File bossBar = new File(assets, "minecraft" + File.separator + "textures" + File.separator + "gui" + File.separator + "sprites" + File.separator + "boss_bar");
        File fonts = new File(namespace, "font");
        File itemDefinitions = new File(namespace, "items");
        File itemModels = new File(namespace, "models" + File.separator + "item");
        for (File dir : List.of(configs, new File(texturesLegacy, "hud"), new File(texturesLegacy, "items"), new File(textures, "hud"),
                new File(textures, "items"), bossBar, fonts, itemDefinitions, itemModels)) {
            Files.createDirectories(dir.toPath());
        }
        File marker = marker();
        String markerContent = readMarker(marker);
        boolean refresh = versionOf(markerContent) < ASSET_VERSION;
        boolean previouslyPending = marker.isFile() && !markerContent.contains(MARKER_ZIPPED);
        List<File> written = new ArrayList<>();

        write(new File(configs, "tasticgames_hud.yml"), hudConfig(), refresh, written);
        write(new File(configs, "tasticgames_items.yml"), itemsConfig(), refresh, written);
        for (File folder : List.of(texturesLegacy, textures)) {
            writeImage(new File(folder, "hud" + File.separator + "box_left.png"), box(CAP_WIDTH, true, false), refresh, written);
            writeImage(new File(folder, "hud" + File.separator + "box_mid.png"), box(MID_WIDTH, false, false), refresh, written);
            writeImage(new File(folder, "hud" + File.separator + "box_right.png"), box(CAP_WIDTH, false, true), refresh, written);
            writeImage(new File(folder, "items" + File.separator + "profile.png"), profileFace(), refresh, written);
        }
        write(new File(fonts, PixelOffsets.FONT_NAME + ".json"), PixelOffsets.fontJson(), refresh, written);
        write(new File(itemDefinitions, "profile_head.json"), profileHeadDefinition(), refresh, written);
        write(new File(itemModels, "profile_head.json"), profileHeadModel(), refresh, written);

        String color = bossbarColor.toLowerCase(Locale.ROOT);
        BufferedImage transparent = new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB);
        for (String name : List.of(color + "_background.png", color + "_progress.png")) {
            writeImage(new File(bossBar, name), transparent, refresh, written);
        }
        for (String notch : List.of("6", "10", "12", "20")) {
            for (String kind : List.of("background", "progress")) {
                writeImage(new File(bossBar, "notched_" + notch + "_" + kind + ".png"), transparent, refresh, written);
            }
        }
        boolean pending = !written.isEmpty() || previouslyPending;
        if (refresh || !written.isEmpty() || !marker.isFile()) {
            writeMarker(marker, !pending);
        }
        if (!written.isEmpty()) {
            logger.info("ItemsAdder content for TasticLobby written to " + contents.getPath() + " (" + written.size() + " files, asset version "
                    + ASSET_VERSION + ") – the pack has to be regenerated (/iazip) so it ships them.");
        } else if (previouslyPending) {
            logger.info("ItemsAdder content for TasticLobby is current (asset version " + ASSET_VERSION + ") but the pack was not regenerated after the last export – /iazip pending.");
        }
        return new Result(written, pending);
    }

    /** Records that the ItemsAdder pack was regenerated after the last export (boxes/items may be used). */
    public void markPackCurrent() {
        try {
            File marker = marker();
            if (versionOf(readMarker(marker)) == ASSET_VERSION) {
                writeMarker(marker, true);
            }
        } catch (IOException e) {
            logger.warning("HUD asset marker could not be updated: " + e.getMessage());
        }
    }

    private static String readMarker(File marker) {
        try {
            return marker.isFile() ? Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMarker(File marker, boolean zipped) throws IOException {
        Files.createDirectories(marker.toPath().getParent());
        Files.writeString(marker.toPath(), ASSET_VERSION + (zipped ? " " + MARKER_ZIPPED : "") + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static int versionOf(String markerContent) {
        if (markerContent == null || markerContent.isBlank()) {
            return 0;
        }
        String first = markerContent.trim().split("\\s+")[0];
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void write(File file, String content, boolean refresh, List<File> written) throws IOException {
        if (file.exists() && !refresh) {
            return;
        }
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        written.add(file);
    }

    private static void writeImage(File file, BufferedImage image, boolean refresh, List<File> written) throws IOException {
        if (file.exists() && !refresh) {
            return;
        }
        ImageIO.write(image, "png", file);
        written.add(file);
    }

    private static String hudConfig() {
        return """
                # Generated by TasticLobby (asset version %d) – HUD box glyphs (dark rounded background behind HUD cells).
                info:
                  namespace: %s
                font_images:
                  hud_box_left:
                    path: hud/box_left.png
                    scale_ratio: %d
                    y_position: %d
                    show_in_gui: false
                  hud_box_mid:
                    path: hud/box_mid.png
                    scale_ratio: %d
                    y_position: %d
                    show_in_gui: false
                  hud_box_right:
                    path: hud/box_right.png
                    scale_ratio: %d
                    y_position: %d
                    show_in_gui: false
                """.formatted(ASSET_VERSION, PixelOffsets.NAMESPACE, BOX_HEIGHT, BOX_Y_POSITION, BOX_HEIGHT, BOX_Y_POSITION, BOX_HEIGHT, BOX_Y_POSITION);
    }

    private static String itemsConfig() {
        return """
                # Generated by TasticLobby (asset version %d) – flat 2D profile icon (static art) for the lobby hotbar.
                # The default profile item is the player's own head instead (item model %s, see items.yml).
                info:
                  namespace: %s
                items:
                  profile_icon:
                    display_name: "Profile"
                    resource:
                      material: PAPER
                      generate: true
                      textures:
                        - items/profile.png
                """.formatted(ASSET_VERSION, PROFILE_HEAD_MODEL, PixelOffsets.NAMESPACE);
    }

    /** items/profile_head.json – item model definition: the vanilla player-head special renderer with our transforms. */
    static String profileHeadDefinition() {
        return """
                {
                  "model": {
                    "type": "minecraft:special",
                    "base": "%s:item/profile_head",
                    "model": {
                      "type": "minecraft:head",
                      "kind": "player"
                    }
                  }
                }
                """.formatted(PixelOffsets.NAMESPACE);
    }

    /**
     * models/item/profile_head.json – display transforms: in the GUI the head is shown straight from the
     * front (rotation 0) and scaled up so it fills the hotbar slot; every other context inherits vanilla.
     */
    static String profileHeadModel() {
        return String.format(Locale.ROOT, """
                {
                  "parent": "minecraft:item/template_skull",
                  "gui_light": "front",
                  "display": {
                    "gui": {
                      "rotation": [0, 0, 0],
                      "translation": [0, %.2f, 0],
                      "scale": [%.2f, %.2f, %.2f]
                    }
                  }
                }
                """, PROFILE_HEAD_GUI_TRANSLATION_Y, PROFILE_HEAD_GUI_SCALE, PROFILE_HEAD_GUI_SCALE, PROFILE_HEAD_GUI_SCALE);
    }

    /**
     * Rounded dark box segment with a subtle 1 px border. Boss bar names are drawn with a text shadow, so
     * every glyph is rendered twice (main + shadow copy 1 px right/down): the fill alpha is chosen for the
     * combined result (~80 % opaque) rather than for a single pass.
     */
    static BufferedImage box(int width, boolean roundLeft, boolean roundRight) {
        BufferedImage image = new BufferedImage(width, BOX_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        int fill = argb(140, 14, 14, 20);
        int border = argb(200, 96, 96, 110);
        for (int y = 0; y < BOX_HEIGHT; y++) {
            for (int x = 0; x < width; x++) {
                int distLeft = roundLeft ? x : Integer.MAX_VALUE;
                int distRight = roundRight ? width - 1 - x : Integer.MAX_VALUE;
                int distSide = Math.min(distLeft, distRight);
                int distVertical = Math.min(y, BOX_HEIGHT - 1 - y);
                if (distSide != Integer.MAX_VALUE && distSide + distVertical < 2) {
                    image.setRGB(x, y, 0); // clipped corner pixels
                    continue;
                }
                boolean edge = distVertical == 0 || distSide == 0 || (distSide != Integer.MAX_VALUE && distSide + distVertical == 2);
                image.setRGB(x, y, edge ? border : fill);
            }
        }
        return image;
    }

    /** 16x16 flat player face (classic skin colours) – static fallback art for {@link #PROFILE_ITEM}. */
    static BufferedImage profileFace() {
        int h = argb(255, 74, 47, 27);     // hair
        int s = argb(255, 201, 156, 124);  // skin
        int d = argb(255, 176, 128, 96);   // shaded skin
        int w = argb(255, 255, 255, 255);  // eye white
        int e = argb(255, 78, 62, 160);    // iris
        int n = argb(255, 139, 90, 60);    // nose
        int m = argb(255, 110, 65, 48);    // mouth
        int[][] face = {
                {h, h, h, h, h, h, h, h},
                {h, h, h, h, h, h, h, h},
                {h, s, s, s, s, s, s, h},
                {s, s, s, s, s, s, s, s},
                {s, w, e, s, s, e, w, s},
                {s, s, s, n, n, s, s, s},
                {s, d, m, m, m, m, d, s},
                {s, s, s, s, s, s, s, s},
        };
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, face[y / 2][x / 2]);
            }
        }
        return image;
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
