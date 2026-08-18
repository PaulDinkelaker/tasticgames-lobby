package de.tasticgames.lobby.npc;

import de.tasticgames.client.dto.network.ServerTypeResponse;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyNpcConfigurationTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                LobbyNpcConfigurationTest.class.getClassLoader().getResourceAsStream("config/npcs.yml"), StandardCharsets.UTF_8));
    }

    private static LobbyNpcConfiguration.Npc npc(String id) {
        return LobbyNpcConfiguration.load(bundled(), "spawn").npcs().get(id);
    }

    @Test
    void bundledDefaultsDescribeTheFiveServiceNpcs() {
        LobbyNpcConfiguration configuration = LobbyNpcConfiguration.load(bundled(), "spawn");
        assertTrue(configuration.enabled());
        assertTrue(configuration.lookClose());
        assertEquals(Set.of("pass", "creative", "survival", "duels", "games"), configuration.npcs().keySet());
    }

    @Test
    void coordinatesAndActionsMatchTheLobbyLayout() {
        assertLocation(npc("pass"), -72.5, 36.0, 11.5, 180.0f);
        assertLocation(npc("creative"), -77.5, 36.0, 1.5, -90.0f);
        assertLocation(npc("survival"), -76.5, 36.0, -3.5, -90.0f);
        assertLocation(npc("duels"), -77.5, 36.0, -8.5, -90.0f);
        assertLocation(npc("games"), -72.5, 36.0, -18.5, -90.0f);

        assertEquals(LobbyNpcAction.PASS, npc("pass").action());
        assertEquals(LobbyNpcAction.COOKIE, npc("games").action());
        assertEquals(LobbyNpcAction.TRANSFER, npc("creative").action());
        assertEquals(ServerTypeResponse.CREATIVE, npc("creative").target());
        assertEquals(ServerTypeResponse.SURVIVAL, npc("survival").target());
        assertEquals(ServerTypeResponse.DUELS, npc("duels").target());
        assertNull(npc("pass").target());
        assertNull(npc("games").target());
    }

    @Test
    void everyNpcStandsInTheLobbyWorld() {
        assertEquals("spawn", npc("pass").world(), "the pass NPC names its world explicitly");
        for (String id : List.of("creative", "survival", "duels", "games")) {
            assertEquals("spawn", npc(id).world(), id + " falls back to the lobby world");
        }
    }

    @Test
    void skinsComeFromTheConfigurationAndSurvivalMirrorsTheViewer() {
        for (String id : List.of("pass", "creative", "duels", "games")) {
            LobbyNpcConfiguration.Npc npc = npc(id);
            assertFalse(npc.mirrorSkin(), id + " uses a fixed skin");
            assertFalse(npc.skinValue().isBlank(), id + " needs a texture value");
            assertFalse(npc.skinSignature().isBlank(), id + " needs a texture signature");
        }
        LobbyNpcConfiguration.Npc survival = npc("survival");
        assertTrue(survival.mirrorSkin(), "the survival NPC shows every player their own skin");
        assertTrue(survival.skinValue().isBlank());
        assertTrue(survival.skinSignature().isBlank());
    }

    @Test
    void invalidValuesFailFast() {
        YamlConfiguration unknownAction = bundled();
        unknownAction.set("npcs.pass.action", "DANCE");
        assertThrows(IllegalArgumentException.class, () -> LobbyNpcConfiguration.load(unknownAction, "spawn"));

        YamlConfiguration unknownTarget = bundled();
        unknownTarget.set("npcs.duels.target", "ARCADE");
        assertThrows(IllegalArgumentException.class, () -> LobbyNpcConfiguration.load(unknownTarget, "spawn"));

        YamlConfiguration withoutTarget = bundled();
        withoutTarget.set("npcs.duels.target", null);
        assertThrows(IllegalArgumentException.class, () -> LobbyNpcConfiguration.load(withoutTarget, "spawn"));

        YamlConfiguration withoutCoordinates = bundled();
        withoutCoordinates.set("npcs.games.z", null);
        assertThrows(IllegalArgumentException.class, () -> LobbyNpcConfiguration.load(withoutCoordinates, "spawn"));
    }

    @Test
    void emptyFileIsEnabledWithoutNpcs() {
        LobbyNpcConfiguration configuration = LobbyNpcConfiguration.load(new YamlConfiguration(), "spawn");
        assertTrue(configuration.enabled());
        assertTrue(configuration.lookClose());
        assertTrue(configuration.npcs().isEmpty());
    }

    private static void assertLocation(LobbyNpcConfiguration.Npc npc, double x, double y, double z, float yaw) {
        assertEquals(x, npc.x(), 1e-9, npc.id() + " x");
        assertEquals(y, npc.y(), 1e-9, npc.id() + " y");
        assertEquals(z, npc.z(), 1e-9, npc.id() + " z");
        assertEquals(yaw, npc.yaw(), 1e-6f, npc.id() + " yaw");
    }
}
