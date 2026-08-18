package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieProfileResponse;
import de.tasticgames.client.dto.lobby.CookieProfileSaveRequest;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
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
        CookieConfiguration configuration = CookieConfiguration.load(yaml, "spawn");
        assertEquals("cookie", configuration.openWorld().name());
        assertEquals(10, configuration.openWorld().requiredPrestige());
        assertEquals("spawn", configuration.mainCookie().world());
        assertEquals(-62.5, configuration.mainCookie().location().x(), 1e-9);
        assertEquals(35.0, configuration.mainCookie().location().y(), 1e-9);
        assertEquals(12.5, configuration.mainCookie().location().z(), 1e-9);
        assertEquals("fv_cookie_clicker", configuration.mainCookie().mythicMobsType());
        assertEquals("fv_cookie_clicker_hit", configuration.mainCookie().clickSkill());
        assertEquals(1, configuration.mainCookie().specialAreas().size());
        assertTrue(!configuration.legacyFile());
        for (var zone : CookieCatalog.defaults().zones()) {
            assertTrue(configuration.zones().containsKey(zone.id()), "layout missing zone " + zone.id());
        }
        assertEquals(2, configuration.npcs().list().size(), "only the baker and the merchant remain (config-version 3)");
        assertTrue(configuration.npcs().list().containsKey("mama_bakewell"));
        assertTrue(configuration.npcs().list().containsKey("gustave"));
        assertTrue(configuration.npcs().list().values().stream().allMatch(n -> n.location().world().equals("spawn")));
        assertTrue(configuration.mainCookie().specialAuto(), "special cookies activate directly by default");
        assertTrue(configuration.openWorld().specialAuto());
        assertEquals(CookieConfiguration.CURRENT_VERSION, yaml.getInt("config-version"));
        assertTrue(configuration.pois().containsKey("cookie.main_cookie"));
        assertEquals("spawn", configuration.pois().get("cookie.main_cookie").location().world());
        assertEquals(1.15, configuration.balancing().costGrowth(), 1e-9);
        assertTrue(!configuration.balancing().offlineEnabled(), "offline production is disabled by default (generators only run inside the cookie zone)");
        assertEquals(8.0, configuration.mainCookie().zoneRadius(), 1e-9);
        assertTrue(configuration.npcs().list().values().stream().allMatch(n -> !n.skinValue().isBlank() && !n.skinSignature().isBlank()));
        // special cookies: the bundled file carries the shipped balancing
        assertEquals(900, configuration.balancing().specialMinIntervalSeconds());
        assertEquals(7200, configuration.balancing().specialMaxIntervalSeconds());
        assertEquals(300, configuration.balancing().specialFloorIntervalSeconds());
        assertEquals(45, configuration.balancing().specialLifetimeSeconds());
        assertEquals(60, configuration.balancing().specialClickClampCpsSeconds());
        for (SpecialCookieRarity rarity : SpecialCookieRarity.values()) {
            assertEquals(rarity.baseWeight(), configuration.balancing().tuning(rarity).weight(), 1e-9, rarity + " weight");
        }
        assertEquals(7777.0, configuration.balancing().tuning(SpecialCookieRarity.MASTER).clickFrenzyMultiplier(), 1e-9);
        assertEquals(0, configuration.balancing().tuning(SpecialCookieRarity.SILVER).rewardWeight(GoldenRewardType.CHAIN_BONUS));
        assertEquals(15, configuration.balancing().tuning(SpecialCookieRarity.DIAMOND).rewardWeight(GoldenRewardType.BLESSING));
    }

    @Test
    void balancingOverridesApplyPerRarity() throws Exception {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", CookieConfiguration.CURRENT_VERSION);
        yaml.set("balancing.special.min-interval-seconds", 60);
        yaml.set("balancing.special.max-interval-seconds", 120);
        yaml.set("balancing.special.rarities.master.weight", 5.0);
        yaml.set("balancing.special.rarities.master.frenzy.multiplier", 99.0);
        yaml.setDefaults(bundled);
        CookieConfiguration configuration = CookieConfiguration.load(yaml, "spawn");
        assertEquals(60, configuration.balancing().specialMinIntervalSeconds());
        assertEquals(120, configuration.balancing().specialMaxIntervalSeconds());
        assertEquals(5.0, configuration.balancing().tuning(SpecialCookieRarity.MASTER).weight(), 1e-9);
        assertEquals(99.0, configuration.balancing().tuning(SpecialCookieRarity.MASTER).frenzyMultiplier(), 1e-9);
        assertEquals(3, configuration.balancing().tuning(SpecialCookieRarity.MASTER).clickFrenzySeconds(), "untouched values are kept");
        assertEquals(60.0, configuration.balancing().tuning(SpecialCookieRarity.SILVER).weight(), 1e-9);

        yaml.set("balancing.special.rarities.bronze.weight", 1.0);
        assertThrows(IllegalArgumentException.class, () -> CookieConfiguration.load(yaml, "spawn"));
    }

    @Test
    void migrationRemovesRetiredNpcsOnce() throws Exception {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration v2 = new YamlConfiguration();
        v2.set("config-version", 2);
        v2.set("main-cookie.location.x", -1.5);
        v2.set("npcs.list.mama_bakewell.location.x", 1.0);
        v2.set("npcs.list.babette.location.x", 2.0);
        v2.set("npcs.list.king_frosting.location.x", 3.0);
        v2.set("npcs.list.custom_guide.location.x", 4.0);
        v2.setDefaults(bundled);
        List<String> changes = CookieConfiguration.migrate(v2);
        assertEquals(3, changes.size(), changes.toString());
        assertEquals(4, CookieConfiguration.CURRENT_VERSION);
        assertEquals(CookieConfiguration.CURRENT_VERSION, v2.getInt("config-version"));
        assertTrue(!v2.isConfigurationSection("npcs.list.babette"));
        assertTrue(!v2.isConfigurationSection("npcs.list.king_frosting"));
        assertTrue(v2.isConfigurationSection("npcs.list.custom_guide"), "foreign NPCs are kept");
        assertEquals(-1.5, v2.getDouble("main-cookie.location.x"), 1e-9, "own layout values are kept");
        assertTrue(CookieConfiguration.migrate(v2).isEmpty(), "migration is idempotent");
        // a 1.0.0 layout file is not migrated in place (its layout is replaced by the bundled defaults)
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("world.name", "cookie");
        legacy.setDefaults(bundled);
        assertTrue(CookieConfiguration.migrate(legacy).isEmpty());
        assertTrue(CookieConfiguration.load(legacy, "spawn").legacyFile());
    }

    @Test
    void migrationRenamesTheGoldenSectionsAndBalancing() throws Exception {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration v3 = new YamlConfiguration();
        v3.set("config-version", 3);
        v3.set("main-cookie.location.x", -1.5);
        v3.set("main-cookie.golden-cookies.enabled", true);
        v3.set("main-cookie.golden-cookies.mode", "spawn");
        v3.set("main-cookie.golden-cookies.areas.bakery_plaza.min.x", -82);
        v3.set("main-cookie.golden-cookies.areas.bakery_plaza.max.x", -42);
        v3.set("open-world.golden-cookies.mode", "auto");
        v3.set("balancing.golden.base-interval-seconds", 300);
        v3.set("balancing.golden.lifetime-seconds", 20);
        v3.set("balancing.golden.buff-seconds", 45);
        v3.setDefaults(bundled);

        List<String> changes = CookieConfiguration.migrate(v3);
        assertEquals(4, changes.size(), changes.toString());
        assertEquals(CookieConfiguration.CURRENT_VERSION, v3.getInt("config-version"));
        assertTrue(!v3.isConfigurationSection("main-cookie.golden-cookies"));
        assertEquals("spawn", v3.getString("main-cookie.special-cookies.mode"), "own settings survive the rename");
        assertEquals(-82, v3.getInt("main-cookie.special-cookies.areas.bakery_plaza.min.x"), "areas survive the rename");
        assertEquals("auto", v3.getString("open-world.special-cookies.mode"));
        assertEquals(20, v3.getInt("balancing.special.lifetime-seconds"), "the spawn lifetime is carried over");
        assertEquals(45, v3.getInt("balancing.special.rarities.golden.frenzy.seconds"), "the golden buff duration is carried over");
        assertTrue(!v3.isSet("balancing.golden.base-interval-seconds"), "the per-second interval has no successor");
        assertTrue(CookieConfiguration.migrate(v3).isEmpty(), "migration is idempotent");

        CookieConfiguration configuration = CookieConfiguration.load(v3, "spawn");
        assertTrue(!configuration.mainCookie().specialAuto());
        assertEquals(1, configuration.mainCookie().specialAreas().size());
        assertEquals(20, configuration.balancing().specialLifetimeSeconds());
        assertEquals(45, configuration.balancing().tuning(SpecialCookieRarity.GOLDEN).frenzySeconds());
        assertEquals(900, configuration.balancing().specialMinIntervalSeconds(), "the new interval defaults apply");
    }

    @Test
    void unmigratedGoldenKeysStillWork() throws Exception {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration v3 = new YamlConfiguration();
        v3.set("config-version", 3);
        v3.set("main-cookie.golden-cookies.mode", "spawn");
        v3.set("main-cookie.golden-cookies.enabled", false);
        v3.set("balancing.golden.lifetime-seconds", 25);
        v3.setDefaults(bundled);
        CookieConfiguration configuration = CookieConfiguration.load(v3, "spawn");
        assertTrue(!configuration.mainCookie().specialAuto(), "golden-cookies.mode is still read");
        assertTrue(!configuration.mainCookie().specialEnabled());
        assertEquals(25, configuration.balancing().specialLifetimeSeconds(), "balancing.golden is still read");
    }

    @Test
    void specialModeIsValidated() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", CookieConfiguration.CURRENT_VERSION);
        yaml.set("main-cookie.special-cookies.mode", "spawn");
        yaml.setDefaults(bundled);
        CookieConfiguration configuration = CookieConfiguration.load(yaml, "spawn");
        assertTrue(!configuration.mainCookie().specialAuto());
        assertTrue(configuration.openWorld().specialAuto());
        yaml.set("main-cookie.special-cookies.mode", "sometimes");
        assertThrows(IllegalArgumentException.class, () -> CookieConfiguration.load(yaml, "spawn"));
    }

    @Test
    void invalidLayoutFailsFast() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("main-cookie.world", "x");
        assertThrows(IllegalArgumentException.class, () -> CookieConfiguration.load(yaml, "world"));
    }

    @Test
    void legacyFileFallsBackToBundledLayout() throws Exception {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config/cookie-clicker.yml"), StandardCharsets.UTF_8));
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("world.name", "cookie");
        legacy.set("world.main-cookie.x", 0.5);
        legacy.set("runtime.save-interval-seconds", 7);
        legacy.setDefaults(bundled);
        CookieConfiguration configuration = CookieConfiguration.load(legacy, "spawn");
        assertTrue(configuration.legacyFile());
        assertEquals("spawn", configuration.mainCookie().world());
        assertEquals(7, configuration.runtime().saveIntervalSeconds());
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
