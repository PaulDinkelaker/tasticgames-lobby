package de.tasticgames.lobby.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * Pixel exact cursor offsets for HUD composition. Minecraft's {@code space} font provider (1.19.4+)
 * gives characters an exact advance, so a negative offset moves the cursor back without drawing
 * anything. The font is shipped inside the exported ItemsAdder content
 * ({@code assets/tasticgames/font/space.json}) – see {@link HudAssetExporter}.
 * <p>
 * ItemsAdder's own {@code applyPixelsOffsetToString} is not used: it returns nothing for empty
 * input, which silently dropped every offset and pushed the background boxes next to the text.
 */
public final class PixelOffsets {

    public static final String NAMESPACE = "tasticgames";
    public static final String FONT_NAME = "space";
    public static final Key FONT = Key.key(NAMESPACE, FONT_NAME);

    /** Powers of two the offsets are composed from (largest first). */
    private static final int[] POWERS = {256, 128, 64, 32, 16, 8, 4, 2, 1};
    private static final char NEGATIVE_BASE = '\uE100';
    private static final char POSITIVE_BASE = '\uE110';

    private PixelOffsets() {
    }

    /** Component that moves the cursor by {@code pixels} (negative = left) and draws nothing. */
    public static Component of(int pixels) {
        if (pixels == 0) {
            return Component.empty();
        }
        return Component.text(chars(pixels)).font(FONT);
    }

    /** Raw characters for {@code pixels} (must be rendered with {@link #FONT}). */
    static String chars(int pixels) {
        StringBuilder builder = new StringBuilder();
        boolean negative = pixels < 0;
        int remaining = Math.abs(pixels);
        for (int i = 0; i < POWERS.length && remaining > 0; i++) {
            char glyph = (char) ((negative ? NEGATIVE_BASE : POSITIVE_BASE) + i);
            while (remaining >= POWERS[i]) {
                builder.append(glyph);
                remaining -= POWERS[i];
            }
        }
        return builder.toString();
    }

    /** The font definition written into the resource pack. */
    public static String fontJson() {
        StringBuilder advances = new StringBuilder();
        for (int i = 0; i < POWERS.length; i++) {
            if (i > 0) {
                advances.append(",\n");
            }
            advances.append(String.format("        \"\\u%04X\": %d,%n        \"\\u%04X\": %d",
                    NEGATIVE_BASE + i, -POWERS[i], POSITIVE_BASE + i, POWERS[i]));
        }
        return """
                {
                  "providers": [
                    {
                      "type": "space",
                      "advances": {
                %s
                      }
                    }
                  ]
                }
                """.formatted(advances.toString());
    }
}
