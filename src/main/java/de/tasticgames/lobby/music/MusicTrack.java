package de.tasticgames.lobby.music;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * One installed soundtrack file.
 *
 * @param id              sound id below {@code tasticgames:ost.} (lower case, ascii)
 * @param title           human readable title derived from the file name
 * @param source          the file the operator dropped into {@code plugins/TasticLobby/ost/}
 * @param durationSeconds playing time read from the Ogg header
 */
public record MusicTrack(String id, String title, Path source, double durationSeconds) {

    public MusicTrack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(source, "source");
    }

    public String soundKey() {
        return "tasticgames:ost." + id;
    }

    public int roundedSeconds() {
        return (int) Math.max(1, Math.round(durationSeconds));
    }

    /** Derives id and title from the file name: {@code ES_Bohemian-Bed-Franz-Gordon.ogg}. */
    public static MusicTrack of(Path file, OggInfo info) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.regionMatches(true, 0, "ES_", 0, 3)) {
            base = base.substring(3); // Epidemic Sound export prefix
        }
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String title = base.replace('_', ' ').replace('-', ' ').replaceAll("\s+", " ").trim();
        return new MusicTrack(id.isBlank() ? "track" : id, title.isBlank() ? id : title, file, info.durationSeconds());
    }
}
