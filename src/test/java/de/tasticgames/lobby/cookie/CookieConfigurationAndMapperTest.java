package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieProfileResponse;
import de.tasticgames.client.dto.lobby.CookieProfileSaveRequest;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieConfigurationAndMapperTest {

    @Test
    void defaultLayoutCoversEveryCatalogZone() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        CookieConfiguration configuration = CookieConfiguration.load(yaml, "world");
        assertEquals("cookie", configuration.world().name());
        for (var zone : CookieCatalog.defaults().zones()) {
            assertTrue(configuration.zones().containsKey(zone.id()), "layout missing zone " + zone.id());
        }
        assertEquals(4, configuration.npcs().size());
        assertTrue(configuration.pois().containsKey("cookie.main_cookie"));
        assertEquals(1.15, configuration.balancing().costGrowth(), 1e-9);
        assertTrue(configuration.balancing().offlineEnabled());
    }

    @Test
    void invalidLayoutFailsFast() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world.name", "x");
        assertThrows(IllegalArgumentException.class, () -> CookieConfiguration.load(yaml, "world"));
    }

    @Test
    void mapperRoundTripKeepsPrecisionAndCrumbs() {
        UUID player = UUID.randomUUID();
        CookieProfileResponse response = new CookieProfileResponse(player, "123456789012345678901234567890.5", "999999999999999999999999999999999999",
                7, "42", 1000, 5, 3600, 4, Map.of("cursor", 25, "baker", 3), List.of("carpal_tunnel"),
                Map.of("iron_fingers", 2, "_crumbs_earned_total", 60), List.of("first_cookie"), List.of("bakery_square"),
                Instant.parse("2026-08-16T10:00:00Z"), Instant.parse("2026-08-16T10:00:05Z"), null, Instant.parse("2026-08-01T00:00:00Z"), 12);
        CookieProfile profile = CookieProfileMapper.fromResponse(response);
        assertEquals("123456789012345678901234567890.5", profile.cookies().toPlainString());
        assertEquals(7, profile.prestigeLevel());
        assertEquals(42, profile.crumbs());
        assertEquals(60, profile.crumbsEarnedTotal());
        assertEquals(2, profile.prestigeUpgradeLevel("iron_fingers"));
        assertEquals(12, profile.version());
        assertEquals(Instant.EPOCH, profile.offlineClaimedUntil());

        CookieProfileSaveRequest save = CookieProfileMapper.toSaveRequest(profile);
        assertEquals(12, save.expectedVersion());
        assertEquals("999999999999999999999999999999999999", save.lifetimeCookies());
        assertEquals("42", save.prestigeCurrency());
        assertEquals(60, save.prestigeUpgrades().get("_crumbs_earned_total"));
        assertEquals(2, save.prestigeUpgrades().get("iron_fingers"));
        assertEquals(25, save.generators().get("cursor"));
        assertTrue(save.upgrades().contains("carpal_tunnel"));
    }
}
