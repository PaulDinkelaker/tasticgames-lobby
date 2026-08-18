package de.tasticgames.lobby.pass;

import de.tasticgames.pass.PassXpSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassConfigurationTest {

    private static YamlConfiguration bundled() throws Exception {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PassConfigurationTest.class.getClassLoader().getResourceAsStream("config/pass.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void bundledDefaultsParse() throws Exception {
        PassConfiguration configuration = PassConfiguration.load(bundled());
        assertTrue(configuration.enabled());
        assertTrue(configuration.npcEnabled());
        assertTrue(configuration.dialogsEnabled());
        assertEquals(10, configuration.leaderboardSize());
        assertEquals(10, configuration.xp().flushIntervalSeconds());
        assertEquals(25, configuration.xp().clicksPerXp());
        assertEquals(2, configuration.xp().perPurchase());
        assertEquals(500, configuration.xp().perPrestige());
        assertEquals(15, configuration.xp().perGolden());
        assertEquals(50, configuration.xp().perZone());
        assertEquals(0, configuration.xp().perAchievement(), "the API already grants the achievement's own XP");
        assertEquals(100, configuration.xp().perNpcQuest());
        assertEquals("pass.premium.howto", configuration.premium().infoKey());
        assertTrue(configuration.premium().storeUrl().startsWith("https://"));
    }

    @Test
    void missingSectionsFallBackToDefaults() {
        PassConfiguration configuration = PassConfiguration.load(new YamlConfiguration());
        assertTrue(configuration.enabled());
        assertEquals(25, configuration.xp().clicksPerXp());
        assertEquals("pass.premium.howto", configuration.premium().infoKey());
        assertEquals("", configuration.premium().storeUrl());
    }

    @Test
    void invalidValuesFailFast() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("xp.clicks-per-xp", 0);
        assertThrows(IllegalArgumentException.class, () -> PassConfiguration.load(yaml));
        YamlConfiguration negative = bundled();
        negative.set("xp.per-golden", -1);
        assertThrows(IllegalArgumentException.class, () -> PassConfiguration.load(negative));
        YamlConfiguration board = bundled();
        board.set("leaderboard-size", 1);
        assertThrows(IllegalArgumentException.class, () -> PassConfiguration.load(board));
    }

    @Test
    void flushIntervalIsClamped() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("xp.flush-interval-seconds", 0);
        assertEquals(1, PassConfiguration.load(yaml).xp().flushIntervalSeconds());
        yaml.set("xp.flush-interval-seconds", 100000);
        assertEquals(300, PassConfiguration.load(yaml).xp().flushIntervalSeconds());
    }

    @Test
    void xpRatesMapToTheCookieHooks() throws Exception {
        PassConfiguration.Xp rates = PassConfiguration.load(bundled()).xp();
        assertEquals(6, PassXpService.xpFor(rates, PassXpSource.COOKIE_PURCHASE, 3));
        assertEquals(500, PassXpService.xpFor(rates, PassXpSource.COOKIE_PRESTIGE, 1));
        assertEquals(30, PassXpService.xpFor(rates, PassXpSource.COOKIE_GOLDEN, 2));
        assertEquals(50, PassXpService.xpFor(rates, PassXpSource.COOKIE_ZONE, 1));
        assertEquals(100, PassXpService.xpFor(rates, PassXpSource.QUEST, 1));
        assertEquals(0, PassXpService.xpFor(rates, PassXpSource.ACHIEVEMENT, 5), "the achievement bonus is off by default");
        assertEquals(0, PassXpService.xpFor(rates, PassXpSource.PLAYTIME, 10), "the lobby reports no playtime XP");
        assertEquals(0, PassXpService.xpFor(rates, PassXpSource.COOKIE_PURCHASE, 0));
    }

    @Test
    void clicksAreConvertedWithACarriedRemainder() {
        assertEquals(2, PassXpService.clickXp(60, 25));
        assertEquals(10, PassXpService.clickRemainder(60, 25));
        assertEquals(0, PassXpService.clickXp(24, 25));
        assertEquals(24, PassXpService.clickRemainder(24, 25));
        assertEquals(0, PassXpService.clickXp(0, 25));
        assertEquals(0, PassXpService.clickRemainder(0, 25));
    }

    @Test
    void bakedCookiesAreSaturatedIntoQuestMetrics() {
        assertEquals(0, PassXpService.toLong(de.tasticgames.lobby.cookie.domain.model.CookieAmount.ZERO));
        assertEquals(1234, PassXpService.toLong(de.tasticgames.lobby.cookie.domain.model.CookieAmount.parse("1234.9")));
        assertEquals(Long.MAX_VALUE, PassXpService.toLong(de.tasticgames.lobby.cookie.domain.model.CookieAmount.parse("1e30")));
        assertEquals(Long.MAX_VALUE, PassXpService.saturatingAdd(Long.MAX_VALUE, 5));
    }
}
