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
 * dark rounded HUD box glyphs, the pixel-offset font, transparent boss bar textures and the flat 2D
 * profile icon item. Files are versioned ({@link #ASSET_VERSION}): our own files are rewritten when
 * the version marker is older, foreign files are never touched. After an export the operator runs
 * {@code /iazip} once.
 */
public final class HudAssetExporter {

    /** Bump when generated textures/configs change so existing installations pick them up. */
    public static final int ASSET_VERSION = 2;
    public static final int BOX_HEIGHT = 16;
    public static final int BOX_Y_POSITION = 12;
    public static final int CAP_WIDTH = 4;
    public static final int MID_WIDTH = 8;
    public static final String PROFILE_ITEM = "tasticgames:profile_icon";

    private final File itemsAdderFolder;
    private final Logger logger;

    public HudAssetExporter(File itemsAdderFolder, Logger logger) {
        this.itemsAdderFolder = Objects.requireNonNull(itemsAdderFolder);
        this.logger = Objects.requireNonNull(logger);
    }

    /** @return the files that were created or updated (empty when everything is current) */
    public List<File> export(String bossbarColor) throws IOException {
        File contents = new File(itemsAdderFolder, "contents" + File.separator + "tasticgames");
        File configs = new File(contents, "configs");
        // ItemsAdder 3.x resolves texture paths below contents/<pack>/textures/, 4.x below resourcepack/assets/<ns>/textures/ – write both
        File texturesLegacy = new File(contents, "textures");
        File textures = new File(contents, "resourcepack" + File.separator + "assets" + File.separator + PixelOffsets.NAMESPACE + File.separator + "textures");
        File bossBar = new File(contents, "resourcepack" + File.separator + "assets" + File.separator + "minecraft" + File.separator + "textures"
                + File.separator + "gui" + File.separator + "sprites" + File.separator + "boss_bar");
        File fonts = new File(contents, "resourcepack" + File.separator + "assets" + File.separator + PixelOffsets.NAMESPACE + File.separator + "font");
        for (File dir : List.of(configs, new File(texturesLegacy, "hud"), new File(texturesLegacy, "items"), new File(textures, "hud"),
                new File(textures, "items"), bossBar, fonts)) {
            Files.createDirectories(dir.toPath());
        }
        File marker = new File(contents, ".tasticlobby-assets");
        boolean refresh = readVersion(marker) < ASSET_VERSION;
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
        if (refresh || !written.isEmpty()) {
            Files.writeString(marker.toPath(), String.valueOf(ASSET_VERSION), StandardCharsets.UTF_8);
        }
        if (!written.isEmpty()) {
            logger.info("ItemsAdder content for TasticLobby written to " + contents.getPath() + " (" + written.size() + " files, asset version "
                    + ASSET_VERSION + ") – run /iazip once so the pack ships them.");
        }
        return written;
    }

    private static int readVersion(File marker) {
        try {
            return marker.isFile() ? Integer.parseInt(Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim()) : 0;
        } catch (IOException | NumberFormatException e) {
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
                # Generated by TasticLobby (asset version %d) – flat 2D profile icon for the lobby hotbar (replace items/profile.png with your own art).
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
                """.formatted(ASSET_VERSION, PixelOffsets.NAMESPACE);
    }

    /** Rounded dark box segment with a subtle 1 px border. */
    static BufferedImage box(int width, boolean roundLeft, boolean roundRight) {
        BufferedImage image = new BufferedImage(width, BOX_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        int fill = argb(190, 12, 12, 16);
        int border = argb(220, 70, 70, 80);
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

    /** 16x16 flat player face (classic skin colours) – fills the whole hotbar slot. */
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
