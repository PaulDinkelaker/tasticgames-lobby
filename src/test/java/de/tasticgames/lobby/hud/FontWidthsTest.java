package de.tasticgames.lobby.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD boxes are sized from these widths. Devanagari and CJK fall back to the bundled unifont, whose
 * glyphs are far wider than the vanilla ASCII font – estimating them with the ASCII average is what made the
 * Hindi HUD spill out of its boxes.
 */
class FontWidthsTest {

    @Test
    void asciiUsesTheVanillaFontWidths() {
        assertEquals(6, FontWidths.width("a"));
        assertEquals(2, FontWidths.width("i"));
        assertEquals(4, FontWidths.width(" "));
        assertEquals(6 * 5, FontWidths.width("abcde"));
    }

    @Test
    void devanagariIsMeasuredAsUnifont() {
        assertEquals(9, FontWidths.width("क"), "क uses a halfwidth unifont cell");
        // no Indic shaping in the client: matras and the virama are cells of their own
        assertEquals(18, FontWidths.width("कि"));
        assertEquals(18, FontWidths.width("क्"));
        assertTrue(FontWidths.width("प्रेस्टीज")
                > FontWidths.width("Prestige"), "Hindi is wider than the same word in Latin");
    }

    @Test
    void fullwidthScriptsTakeADoubleCell() {
        assertEquals(17, FontWidths.width("你"));
        assertEquals(17, FontWidths.width("가"));
        assertEquals(34, FontWidths.width("你好"));
    }

    @Test
    void emptyTextHasNoWidth() {
        assertEquals(0, FontWidths.width(""));
        assertEquals(0, FontWidths.width(null));
    }
}
