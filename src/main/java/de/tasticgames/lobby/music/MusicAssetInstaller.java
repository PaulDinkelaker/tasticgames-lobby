package de.tasticgames.lobby.music;

import de.tasticgames.lobby.integration.item.CustomItemProvider;
import de.tasticgames.service.Service;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Installs the TasticGames soundtrack: the operator drops the {@code .ogg} files into
 * {@code plugins/TasticLobby/ost/} and this service copies them into the ItemsAdder content pack, writes the
 * matching {@code sounds.json} (so the tracks exist as {@code tasticgames:ost.<id>}) and silences the vanilla
 * music events, so the custom soundtrack replaces Minecraft's music instead of playing on top of it.
 * <p>
 * Track lengths are read from the Ogg headers ({@link OggInfo}) – the playlist needs no hand-written durations.
 * The music files are never bundled with the plugin: they are the operator's licensed content.
 */
public final class MusicAssetInstaller implements Service {

    /** Vanilla music events that are silenced so only the custom soundtrack plays. */
    private static final List<String> VANILLA_MUSIC_EVENTS = List.of(
            "music.menu", "music.game", "music.creative", "music.credits", "music.dragon", "music.end",
            "music.under_water", "music.nether.nether_wastes", "music.nether.soul_sand_valley",
            "music.nether.crimson_forest", "music.nether.warped_forest", "music.nether.basalt_deltas",
            "music.overworld.badlands", "music.overworld.bamboo_jungle", "music.overworld.cherry_grove",
            "music.overworld.deep_dark", "music.overworld.desert", "music.overworld.dripstone_caves",
            "music.overworld.flower_forest", "music.overworld.forest", "music.overworld.frozen_peaks",
            "music.overworld.grove", "music.overworld.jagged_peaks", "music.overworld.jungle",
            "music.overworld.lush_caves", "music.overworld.meadow", "music.overworld.old_growth_taiga",
            "music.overworld.snowy_slopes", "music.overworld.sparse_jungle", "music.overworld.stony_peaks",
            "music.overworld.swamp");

    private final Plugin plugin;
    private final CustomItemProvider customItems;
    private final Logger logger;
    private final boolean enabled;
    private final boolean silenceVanilla;
    private volatile List<MusicTrack> tracks = List.of();
    private volatile boolean installedContent;

    public MusicAssetInstaller(Plugin plugin, CustomItemProvider customItems, boolean enabled, boolean silenceVanilla, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.customItems = Objects.requireNonNull(customItems, "customItems");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
        this.silenceVanilla = silenceVanilla;
    }

    @Override
    public String id() {
        return "music-asset-installer";
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        Path directory = plugin.getDataFolder().toPath().resolve("ost");
        try {
            Files.createDirectories(directory);
            Path readme = directory.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, readme(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.warning("Soundtrack: " + directory + " could not be prepared (" + e.getMessage() + ").");
            return;
        }
        List<MusicTrack> found = scan(directory);
        if (found.isEmpty()) {
            logger.info("Soundtrack: no .ogg files in " + directory + " – the vanilla music keeps playing.");
            return;
        }
        if (!customItems.available()) {
            logger.warning("Soundtrack: ItemsAdder is not installed, so the tracks cannot be shipped to the clients.");
            return;
        }
        Path itemsAdder = plugin.getDataFolder().getParentFile().toPath().resolve("ItemsAdder");
        if (!Files.isDirectory(itemsAdder)) {
            logger.warning("Soundtrack: plugins/ItemsAdder does not exist – tracks not installed.");
            return;
        }
        try {
            install(found, itemsAdder);
            tracks = List.copyOf(found);
            logger.info("Soundtrack: " + found.size() + " tracks ready ("
                    + Math.round(found.stream().mapToDouble(MusicTrack::durationSeconds).sum() / 60.0) + " min"
                    + (silenceVanilla ? ", vanilla music silenced" : "") + ").");
        } catch (IOException e) {
            logger.warning("Soundtrack could not be installed: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        // nothing to release: installation happens once during start
    }

    /** Installed tracks in a stable order (empty when the soundtrack is off or nothing was dropped in). */
    public List<MusicTrack> tracks() {
        return tracks;
    }

    /** Whether this start wrote new files, so the ItemsAdder pack has to be rebuilt. */
    public boolean installedContent() {
        return installedContent;
    }

    private List<MusicTrack> scan(Path directory) {
        List<MusicTrack> found = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".mp3") || name.toLowerCase(Locale.ROOT).endsWith(".wav")) {
                        logger.warning("Soundtrack: " + name + " is not an .ogg file – Minecraft only plays Ogg Vorbis.");
                    }
                    return;
                }
                Optional<OggInfo> info = OggInfo.read(path);
                if (info.isEmpty()) {
                    logger.warning("Soundtrack: " + name + " is not a readable Ogg Vorbis file – skipped.");
                    return;
                }
                found.add(MusicTrack.of(path, info.get()));
            });
        } catch (IOException e) {
            logger.warning("Soundtrack: " + directory + " could not be listed (" + e.getMessage() + ").");
        }
        return found;
    }

    private void install(List<MusicTrack> found, Path itemsAdder) throws IOException {
        Path assets = itemsAdder.resolve("contents").resolve("tasticgames").resolve("resourcepack").resolve("assets");
        Path sounds = assets.resolve("tasticgames").resolve("sounds").resolve("ost");
        Files.createDirectories(sounds);
        for (MusicTrack track : found) {
            Path destination = sounds.resolve(track.id() + ".ogg");
            if (!Files.exists(destination) || Files.size(destination) != Files.size(track.source())) {
                Files.copy(track.source(), destination, StandardCopyOption.REPLACE_EXISTING);
                installedContent = true;
            }
        }
        writeIfChanged(assets.resolve("tasticgames").resolve("sounds.json"), soundsJson(found));
        if (silenceVanilla) {
            writeIfChanged(assets.resolve("minecraft").resolve("sounds.json"), silencedVanillaJson());
        }
    }

    private void writeIfChanged(Path file, String content) throws IOException {
        if (Files.exists(file) && content.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        installedContent = true;
    }

    /** {@code assets/tasticgames/sounds.json}: one streamed music event per track. */
    static String soundsJson(List<MusicTrack> tracks) {
        StringJoiner events = new StringJoiner(",\n");
        for (MusicTrack track : tracks) {
            events.add("""
                      "ost.%s": {
                        "category": "music",
                        "sounds": [
                          {
                            "name": "tasticgames:ost/%s",
                            "stream": true
                          }
                        ]
                      }""".formatted(track.id(), track.id()));
        }
        return "{\n" + events + "\n}\n";
    }

    /** {@code assets/minecraft/sounds.json}: replaces the vanilla music events with nothing. */
    static String silencedVanillaJson() {
        StringJoiner events = new StringJoiner(",\n");
        for (String event : VANILLA_MUSIC_EVENTS) {
            events.add("""
                      "%s": {
                        "replace": true,
                        "sounds": []
                      }""".formatted(event));
        }
        return "{\n" + events + "\n}\n";
    }

    private static String readme() {
        return """
                Drop the TasticGames soundtrack here as .ogg files (Ogg Vorbis, 48 kHz stereo is fine).

                On every server start TasticLobby
                  * copies them into plugins/ItemsAdder/contents/tasticgames/resourcepack/assets/tasticgames/sounds/ost/,
                  * writes the matching sounds.json (the tracks become tasticgames:ost.<file name>),
                  * silences the vanilla music events so only this soundtrack plays (config/music.yml: ost.silence-vanilla),
                  * reads every track length from the file itself - no durations to type anywhere.

                The file name becomes the track id and the shown title: "ES_Bohemian-Bed-Franz-Gordon.ogg" becomes
                the id "bohemian_bed_franz_gordon" and the title "Bohemian Bed - Franz Gordon".

                Players switch the music off in /settings; with the soundtrack off no music plays at all, because the
                vanilla tracks are replaced. Only .ogg works - Minecraft cannot play .mp3 or .wav.
                """;
    }
}
