package de.tasticgames.lobby.cookie.domain.prestige;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Prestige currency ("Heavenly Crumbs") formulas.
 * <p>
 * {@code crumbsFor(lifetime) = floor(cbrt(lifetime / 1e6))}, capped at {@link #MAX_CRUMBS}.
 * The gain on prestige is {@code max(0, crumbsFor(lifetime) - crumbsEarnedTotal)} so the same
 * cookies can never yield crumbs twice.
 */
public final class PrestigeCalculator {

    /** Divisor applied to lifetime cookies before the cube root. */
    public static final BigDecimal CRUMB_DIVISOR = new BigDecimal("1000000");
    /** Sane cap for a long-based currency. */
    public static final long MAX_CRUMBS = 1_000_000_000_000_000L;

    private PrestigeCalculator() {
    }

    /** Total crumbs that a lifetime amount is worth (cumulative, not the gain). */
    public static long crumbsFor(CookieAmount lifetimeCookies) {
        return crumbsFor(lifetimeCookies.toBigDecimal());
    }

    public static long crumbsFor(BigDecimal lifetimeCookies) {
        if (lifetimeCookies.signum() <= 0) return 0;
        BigInteger scaled = lifetimeCookies.divide(CRUMB_DIVISOR, 0, RoundingMode.FLOOR).toBigInteger();
        if (scaled.signum() <= 0) return 0;
        BigInteger root = floorCbrt(scaled);
        BigInteger cap = BigInteger.valueOf(MAX_CRUMBS);
        return root.compareTo(cap) >= 0 ? MAX_CRUMBS : root.longValueExact();
    }

    /** Crumbs gained by a prestige given the already-earned baseline. */
    public static long crumbsGained(CookieAmount lifetimeCookies, long crumbsEarnedTotal) {
        long total = crumbsFor(lifetimeCookies);
        return Math.max(0L, total - crumbsEarnedTotal);
    }

    /** Exact integer cube root (floor) via Newton iteration. */
    static BigInteger floorCbrt(BigInteger n) {
        if (n.signum() < 0) throw new IllegalArgumentException("n must be >= 0");
        if (n.signum() == 0) return BigInteger.ZERO;
        int bits = n.bitLength();
        // initial guess: 2^(ceil(bits/3)) >= cbrt(n)
        BigInteger x = BigInteger.ONE.shiftLeft((bits + 2) / 3);
        BigInteger three = BigInteger.valueOf(3);
        while (true) {
            // y = (2x + n / x^2) / 3
            BigInteger y = x.shiftLeft(1).add(n.divide(x.multiply(x))).divide(three);
            if (y.compareTo(x) >= 0) break;
            x = y;
        }
        // correction (Newton from above converges to floor, but guard against off-by-one)
        while (x.pow(3).compareTo(n) > 0) x = x.subtract(BigInteger.ONE);
        while (x.add(BigInteger.ONE).pow(3).compareTo(n) <= 0) x = x.add(BigInteger.ONE);
        return x;
    }
}
