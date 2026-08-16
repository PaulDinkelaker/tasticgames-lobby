package de.tasticgames.lobby.cookie.domain.model;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static de.tasticgames.lobby.cookie.domain.TestSupport.PLAYER;
import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieProfileTest {

    @Test
    void freshProfileIsEmptyAndClean() {
        CookieProfile p = CookieProfile.fresh(PLAYER);
        assertEquals(PLAYER, p.playerId());
        assertEquals(CookieAmount.ZERO, p.cookies());
        assertEquals(CookieAmount.ZERO, p.lifetimeCookies());
        assertEquals(0, p.prestigeLevel());
        assertEquals(0, p.crumbs());
        assertTrue(p.generators().isEmpty());
        assertNull(p.lastActiveAt());
        assertFalse(p.isDirty());
        assertEquals(T0, CookieProfile.fresh(PLAYER, T0).lastActiveAt());
    }

    @Test
    void mutationsMarkDirtyAndKeepInvariants() {
        CookieProfile p = TestSupport.fresh();
        p.earn(CookieAmount.of(10));
        assertTrue(p.isDirty());
        p.markClean();
        p.spend(CookieAmount.of(4));
        assertEquals(CookieAmount.of(6), p.cookies());
        assertEquals(CookieAmount.of(10), p.lifetimeCookies());
        assertThrows(ArithmeticException.class, () -> p.spend(CookieAmount.of(100)));
        assertThrows(IllegalArgumentException.class, () -> p.addGenerators("cursor", 0));
        assertThrows(IllegalArgumentException.class, () -> p.setPrestigeLevel(-1));
        assertThrows(ArithmeticException.class, () -> p.spendCrumbs(1));
        p.addCrumbs(5);
        p.spendCrumbs(2);
        assertEquals(3, p.crumbs());
        assertEquals(5, p.crumbsEarnedTotal());
        assertTrue(p.isDirty());
        p.setPrestigeUpgradeLevel("iron_fingers", 0);
        assertFalse(p.prestigeUpgrades().containsKey("iron_fingers"));
        assertThrows(UnsupportedOperationException.class, () -> p.generators().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> p.upgrades().add("x"));
    }

    @Test
    void loadAndSnapshotRoundTrip() {
        CookieProfile p = CookieProfile.builder(PLAYER)
                .cookies(CookieAmount.of("123.5"))
                .lifetimeCookies(CookieAmount.of("1e12"))
                .prestigeLevel(3)
                .crumbs(7)
                .crumbsEarnedTotal(100)
                .totalClicks(5000)
                .goldenCookiesClicked(12)
                .playtimeSeconds(3600)
                .highestCombo(4)
                .generators(Map.of("cursor", 10, "oven", 0))
                .upgrades(Set.of("carpal_tunnel"))
                .prestigeUpgrades(Map.of("iron_fingers", 2, "head_start", 0))
                .achievements(Set.of("first_cookie"))
                .discoveredZones(Set.of("bakery_square", "sugar_fields"))
                .lastActiveAt(T0)
                .offlineClaimedUntil(T0.minusSeconds(60))
                .version(9)
                .build();
        assertFalse(p.isDirty());
        assertEquals(0, p.generatorCount("oven"));
        assertEquals(0, p.prestigeUpgradeLevel("head_start"));
        p.addBuff(ActiveBuff.of(BuffType.FRENZY, 7, T0, Duration.ofSeconds(30), "golden"));
        p.setComboState(2, 1, T0.toEpochMilli());

        CookieProfileSnapshot s = p.snapshot();
        assertEquals(PLAYER, s.playerId());
        assertEquals(CookieAmount.of("123.5"), s.cookies());
        assertEquals(CookieAmount.of("1e12"), s.lifetimeCookies());
        assertEquals(3, s.prestigeLevel());
        assertEquals(7, s.crumbs());
        assertEquals(100, s.crumbsEarnedTotal());
        assertEquals(5000, s.totalClicks());
        assertEquals(12, s.goldenCookiesClicked());
        assertEquals(3600, s.playtimeSeconds());
        assertEquals(4, s.highestCombo());
        assertEquals(Map.of("cursor", 10), s.generators());
        assertEquals(10, s.generatorCount("cursor"));
        assertEquals(10, s.totalGenerators());
        assertTrue(s.hasUpgrade("carpal_tunnel"));
        assertEquals(2, s.prestigeUpgradeLevel("iron_fingers"));
        assertEquals(Set.of("first_cookie"), s.achievements());
        assertEquals(2, s.discoveredZones().size());
        assertEquals(T0, s.lastActiveAt());
        assertEquals(T0.minusSeconds(60), s.offlineClaimedUntil());
        assertEquals(9, s.version());
        assertEquals(1, s.activeBuffs().size());
        assertEquals(2, s.comboStage());
        // snapshot is detached
        p.addGenerators("cursor", 5);
        assertEquals(10, s.generatorCount("cursor"));
        assertThrows(UnsupportedOperationException.class, () -> s.generators().put("x", 1));

        // full-args load equals builder
        CookieProfile q = CookieProfile.load(PLAYER, s.cookies(), s.lifetimeCookies(), s.prestigeLevel(), s.crumbs(),
                s.crumbsEarnedTotal(), s.totalClicks(), s.goldenCookiesClicked(), s.playtimeSeconds(), s.highestCombo(),
                s.generators(), s.upgrades(), s.prestigeUpgrades(), s.achievements(), s.discoveredZones(),
                s.lastActiveAt(), s.offlineClaimedUntil(), s.version());
        assertEquals(s.cookies(), q.cookies());
        assertEquals(9, q.version());
        assertEquals(10, q.bumpVersion());
    }

    @Test
    void clickHistoryRingBuffer() {
        ClickHistory h = new ClickHistory(4);
        for (long t = 1; t <= 6; t++) h.record(t);
        assertEquals(4, h.size());
        assertEquals(6, h.last());
        assertEquals(2, h.countAfter(4)); // 5, 6
        assertEquals(4, h.countAfter(0));
        h.clear();
        assertEquals(0, h.size());
        assertEquals(Long.MIN_VALUE, h.last());
        assertThrows(IllegalArgumentException.class, () -> new ClickHistory(0));
    }

    @Test
    void activeBuffValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ActiveBuff(BuffType.FRENZY, -1, T0, T0.plusSeconds(1), "x"));
        assertThrows(IllegalArgumentException.class, () -> new ActiveBuff(BuffType.FRENZY, 2, T0, T0.minusSeconds(1), "x"));
        ActiveBuff b = ActiveBuff.of(BuffType.CPS_MULTIPLIER, 2, T0, Duration.ofSeconds(10), "x");
        assertTrue(b.isActiveAt(T0));
        assertTrue(b.isActiveAt(T0.plusSeconds(9)));
        assertFalse(b.isActiveAt(T0.plusSeconds(10)));
        assertTrue(b.isExpiredAt(T0.plusSeconds(10)));
        assertEquals(Duration.ofSeconds(4), b.remainingAt(T0.plusSeconds(6)));
        assertEquals(Duration.ZERO, b.remainingAt(T0.plusSeconds(60)));
        assertTrue(BuffType.FRENZY.affectsCps() && BuffType.FRENZY.affectsClicks());
        assertFalse(BuffType.CLICK_FRENZY.affectsCps());
    }
}
