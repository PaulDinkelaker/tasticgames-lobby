package de.tasticgames.lobby.hud;

/**
 * Advance widths of Minecraft's default font (glyph width + 1 px spacing) for layout estimates.
 * Non-ASCII characters (Devanagari, symbols) are approximated with the average width.
 */
final class FontWidths {

    private static final int DEFAULT = 6;
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
            } else if (Character.getType(c) == Character.NON_SPACING_MARK) {
                total += 0; // combining marks (Devanagari vowel signs)
            } else {
                total += DEFAULT;
            }
        }
        return total;
    }
}
