package de.tasticgames.lobby.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyConfigurationTest {

    @Test
    void duplicateItemSlotsAreRejected() {
        LobbyConfiguration.ItemSlot a = new LobbyConfiguration.ItemSlot("gateway", 0, Material.COMPASS, "", "", true);
        LobbyConfiguration.ItemSlot b = new LobbyConfiguration.ItemSlot("profile", 0, Material.PLAYER_HEAD, "", "", true);
        assertThrows(IllegalArgumentException.class, () -> new LobbyConfiguration.Items(Map.of("gateway", a, "profile", b), 400));
        LobbyConfiguration.ItemSlot disabled = new LobbyConfiguration.ItemSlot("profile", 0, Material.PLAYER_HEAD, "", "", false);
        assertTrue(new LobbyConfiguration.Items(Map.of("gateway", a, "profile", disabled), 400).slots().size() == 2);
        assertThrows(IllegalArgumentException.class, () -> new LobbyConfiguration.ItemSlot("x", 9, Material.STONE, "", "", true));
    }

    @Test
    void movementRequiresPositiveLaunchVelocity() {
        assertThrows(IllegalArgumentException.class, () -> new LobbyConfiguration.Movement(true, Material.SLIME_BLOCK, 0, 0.9, 600, "s",
                List.of(), true, Material.GOLD_BLOCK, false, List.of(), 1500, "s", false, 0.9, 1500));
    }

    @Test
    void apiCredentialsDetection() {
        assertTrue(!new LobbyConfiguration.Api(true, "https://api/", "svc", "CHANGE_ME", 5, 10, "lobby", "none").credentialsConfigured());
        assertTrue(new LobbyConfiguration.Api(true, "https://api/", "svc", "k", 5, 10, "Lobby-1", "config/api.yml").credentialsConfigured());
        assertTrue(new LobbyConfiguration.Api(true, "https://api/", "svc", "k", 5, 10, "Lobby-1", "config/api.yml").backendServerId().equals("lobby-1"));
    }
}
