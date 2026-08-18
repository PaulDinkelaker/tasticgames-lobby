package de.tasticgames.lobby.music;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The soundtrack becomes ItemsAdder content: ids from file names, sounds.json, silenced vanilla music. */
class SoundtrackTest {

    private static MusicTrack track(String fileName, double seconds) {
        return MusicTrack.of(Path.of("plugins", "TasticLobby", "ost", fileName), new OggInfo(48_000, 2, seconds));
    }

    @Test
    void idAndTitleComeFromTheFileName() {
        MusicTrack t = track("ES_Bohemian-Bed-Franz-Gordon.ogg", 153.4);
        assertEquals("bohemian_bed_franz_gordon", t.id());
        assertEquals("Bohemian Bed Franz Gordon", t.title());
        assertEquals("tasticgames:ost.bohemian_bed_franz_gordon", t.soundKey());
        assertEquals(153, t.roundedSeconds());

        assertEquals("night_shift_2", track("Night Shift (2).ogg", 60).id(), "spaces and brackets become underscores");
        assertEquals("track", track("!!!.ogg", 60).id(), "a name without usable characters still yields an id");
    }

    @Test
    void soundsJsonRegistersEveryTrackAsStreamedMusic() {
        String json = MusicAssetInstaller.soundsJson(List.of(track("ES_First-Light.ogg", 200), track("Second_Wind.ogg", 190)));
        assertTrue(json.startsWith("{"));
        assertTrue(json.trim().endsWith("}"));
        assertTrue(json.contains("\"ost.first_light\""));
        assertTrue(json.contains("\"name\": \"tasticgames:ost/first_light\""));
        assertTrue(json.contains("\"ost.second_wind\""));
        assertTrue(json.contains("\"category\": \"music\""), "the client must treat it as music, not as a sound effect");
        assertTrue(json.contains("\"stream\": true"), "minutes-long files have to stream");
        assertEquals(2, json.split("\"category\": \"music\"", -1).length - 1);
    }

    @Test
    void vanillaMusicIsReplacedNotMixed() {
        String json = MusicAssetInstaller.silencedVanillaJson();
        assertTrue(json.contains("\"music.menu\""));
        assertTrue(json.contains("\"music.game\""));
        assertTrue(json.contains("\"music.overworld.forest\""));
        assertTrue(json.contains("\"replace\": true"));
        assertTrue(json.contains("\"sounds\": []"));
        assertFalse(json.contains("tasticgames:"), "the vanilla file only silences, it never points at our tracks");
    }
}
