package de.tasticgames.lobby.hud.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Findet in einem UI-Icon-Pack heraus, welche Datei die Konfiguration ist und welche PNGs zu den
 * Icons gehören – reine Zeichenkettenarbeit, damit die Regeln ohne Archiv und ohne Server prüfbar
 * sind.
 *
 * <p>Diese Packs sind nicht einheitlich gebaut. Manche liefern einen fertigen ItemsAdder-Ordner
 * ({@code itemsadder/contents/<pack>/...} mit {@code textures/font/}), andere nur lose PNGs plus
 * eine herstellereigene Konfiguration ({@code configs/ItemsAdder/icons.yml} neben {@code icons/}).
 * Fast alle enthalten außerdem Beiwerk, das nicht auf den Server gehört: Vorschaubilder,
 * 312-Pixel-Exporte für Discord, Aseprite-Quellen und Ordner mit ausdrücklich ungeprüften Icons.</p>
 */
public record IconPackLayout(List<String> configCandidates, Map<String, String> icons) {

    /** Ordner, deren Inhalt nie installiert wird. */
    private static final String[] EXCLUDED = {
            "preview", "_312", "/source/", "needs_review", "__macosx", "/.git/"
    };

    public IconPackLayout {
        configCandidates = List.copyOf(configCandidates);
        icons = Map.copyOf(icons);
    }

    public boolean isEmpty() {
        return icons.isEmpty() || configCandidates.isEmpty();
    }

    /**
     * @param entries Pfade im Archiv oder Ordner, mit {@code /} als Trenner
     * @return Konfigurationskandidaten (beste zuerst) und Icon-ID -> Pfad des PNG
     */
    public static IconPackLayout detect(Iterable<String> entries) {
        Objects.requireNonNull(entries, "entries");
        List<String> configs = new ArrayList<>();
        List<String> fontTextures = new ArrayList<>();
        List<String> looseIcons = new ArrayList<>();

        for (String raw : entries) {
            if (raw == null || raw.isBlank() || raw.endsWith("/")) {
                continue;
            }
            String path = raw.replace('\\', '/').replaceAll("/+", "/");
            String lower = path.toLowerCase(Locale.ROOT);
            if (excluded(lower)) {
                continue;
            }
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                configs.add(path);
            } else if (lower.endsWith(".png")) {
                if (lower.contains("/textures/font/") || lower.startsWith("textures/font/")) {
                    fontTextures.add(path);
                } else {
                    looseIcons.add(path);
                }
            }
        }

        // Ein fertiger ItemsAdder-Ordner gewinnt: dort stehen genau die Icons, die der Hersteller
        // freigegeben hat - die losen Ordner enthalten oft mehr, als die Konfiguration kennt.
        List<String> chosen = fontTextures.isEmpty() ? looseIcons : fontTextures;
        Map<String, String> icons = new LinkedHashMap<>();
        for (String path : chosen) {
            String id = id(path);
            // bei gleichem Namen gewinnt der kürzere Pfad: er liegt näher an der Konfiguration
            icons.merge(id, path, (existing, candidate) ->
                    candidate.length() < existing.length() ? candidate : existing);
        }
        configs.sort(IconPackLayout::configPriority);
        return new IconPackLayout(configs, icons);
    }

    /** Dateiname ohne Endung, klein geschrieben – das ist die Icon-ID. */
    public static String id(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static boolean excluded(String lower) {
        for (String marker : EXCLUDED) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Konfigurationen aus einem ItemsAdder-Ordner zuerst, dann kurze Pfade. */
    private static int configPriority(String first, String second) {
        int weighted = Integer.compare(weight(first), weight(second));
        return weighted != 0 ? weighted : Integer.compare(first.length(), second.length());
    }

    private static int weight(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/contents/") || lower.startsWith("contents/")) {
            return 0;
        }
        if (lower.contains("itemsadder")) {
            return 1;
        }
        return 2;
    }
}
