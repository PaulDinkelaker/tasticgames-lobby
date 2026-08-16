package de.tasticgames.lobby.cookie.domain;

import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared helpers for tests. */
public final class TestSupport {

    public static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    public static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TestSupport() {
    }

    public static CookieEngine engine() {
        return new CookieEngine(CookieCatalog.defaults(), CookieBalancing.defaults());
    }

    public static CookieEngine engine(CookieBalancing balancing) {
        return new CookieEngine(CookieCatalog.defaults(), balancing);
    }

    public static CookieProfile fresh() {
        return CookieProfile.fresh(PLAYER, T0);
    }

    /** Profile at the given prestige level with the given bank (lifetime = bank). */
    public static CookieProfile profileAt(int prestige, String cookies) {
        return CookieProfile.builder(PLAYER)
                .prestigeLevel(prestige)
                .cookies(CookieAmount.of(cookies))
                .lifetimeCookies(CookieAmount.of(cookies))
                .lastActiveAt(T0)
                .build();
    }

    public static CookieProfile withCookies(String cookies) {
        CookieProfile p = fresh();
        p.earn(CookieAmount.of(cookies));
        return p;
    }

    public static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    public static void assertBigEquals(String expected, BigDecimal actual) {
        if (new BigDecimal(expected).compareTo(actual) != 0) {
            throw new AssertionError("expected " + expected + " but was " + actual.toPlainString());
        }
    }

    public static void assertAmount(String expected, CookieAmount actual) {
        if (CookieAmount.of(expected).compareTo(actual) != 0) {
            throw new AssertionError("expected " + expected + " but was " + actual.toPlainString());
        }
    }
}
