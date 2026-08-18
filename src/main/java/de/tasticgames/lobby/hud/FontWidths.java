package de.tasticgames.lobby.hud;

/**
 * Advance widths of Minecraft's default font (glyph width + 1 px spacing) for layout estimates.
 * <p>
 * Scripts outside Latin are not part of Minecraft's own bitmap font: the client falls back to the bundled
 * unifont, whose glyphs occupy an 8 px cell (16 px for fullwidth CJK) and therefore advance 9 px (17 px)
 * instead of the ~6 px an average ASCII glyph needs. Estimating Devanagari with the ASCII average made every
 * Hindi HUD line roughly 50 % wider than the box drawn behind it, so the text spilled out of the box.
 */
final class FontWidths {

    private static final int DEFAULT = 6;
    /** Halfwidth unifont cell (8 px glyph + 1 px spacing) – Devanagari, Cyrillic beyond the vanilla font, ... */
    private static final int UNIFONT = 9;
    /** Fullwidth unifont cell (16 px glyph + 1 px spacing) – CJK, Hangul, fullwidth forms. */
    private static final int FULLWIDTH = 17;
    private static final int[] ASCII = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            ASCII[i] = DEFAULT;
        }
        set(' ', 4); set('!', 2); set('"', 5); set('\'', 3); set('(', 5); set(')', 5); set('*', 5); set(',', 2);
        set('.', 2); set(':', 2); set(';', 2); set('<', 5); set('>', 5); set('@', 7); set('I', 4); set('[', 4);
        set(']', 4); set('`', 3); set('f', 5); set('i', 2); set('k', 5); set('l', 3); set('t', 4); set('{', 5);
        set('|', 2); set('}', 5); set('~', 7);
    }

    private FontWidths() {
    }

    private static void set(char c, int width) {
        ASCII[c] = width;
    }

    /** Fullwidth unifont cells (CJK, Hangul, fullwidth forms). */
    private static boolean isFullwidth(char c) {
        return (c >= '\u1100' && c <= '\u115F')
                || (c >= '\u2E80' && c <= '\uA4CF')
                || (c >= '\uAC00' && c <= '\uD7A3')
                || (c >= '\uF900' && c <= '\uFAFF')
                || (c >= '\uFF00' && c <= '\uFF60');
    }

    /** Estimated pixel width of plain text in the default font (no formatting codes). */
    static int width(String text) {
        if (text == null || text.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                total += 9; // emoji / supplementary glyphs
                i++;
            } else if (c < 128) {
                total += ASCII[c];
            } else if (c == '\u00B7' || c == '\u2219') {
                total += 2; // middle dot / bullet operator: 1 px glyph + spacing
            } else if (c == '\u2022') {
                total += 4; // bullet
            } else if (c >= '\u0080' && c <= '\u02FF') {
                total += DEFAULT; // Latin-1 supplement / Latin extended are in the vanilla font
            } else if (isFullwidth(c)) {
                total += FULLWIDTH; // CJK and Hangul use a fullwidth unifont cell
            } else {
                // Devanagari and every other unifont fallback glyph. The client does no Indic shaping: every
                // code point gets its own cell, combining marks (matras, virama) included - which is exactly
                // why a Hindi line needs far more pixels than the same text in Latin letters.
                total += UNIFONT;
            }
        }
        return total;
    }
}
