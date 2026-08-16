package de.tasticgames.lobby.cookie.domain.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Small BigDecimal helpers that avoid double overflow. */
public final class BigMath {

    private BigMath() {
    }

    /** log10 of a positive BigDecimal without converting the whole value to double. */
    public static double log10(BigDecimal value) {
        if (value.signum() <= 0) throw new IllegalArgumentException("log10 of non-positive value");
        BigDecimal stripped = value.stripTrailingZeros();
        int exponent = stripped.precision() - stripped.scale() - 1;   // value = m * 10^exponent, 1 <= m < 10
        BigDecimal mantissa = stripped.movePointLeft(exponent);
        return exponent + Math.log10(mantissa.doubleValue());
    }

    /** Multiplies with a double factor using DECIMAL128 precision. */
    public static BigDecimal times(BigDecimal value, double factor) {
        return value.multiply(BigDecimal.valueOf(factor), MathContext.DECIMAL128);
    }

    /** {@code seconds} as BigDecimal from nanoseconds. */
    public static BigDecimal secondsOf(java.time.Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    /** Non-negative floor to a long, clamped at Long.MAX_VALUE. */
    public static long floorToLong(BigDecimal value) {
        if (value.signum() <= 0) return 0L;
        BigDecimal floored = value.setScale(0, RoundingMode.FLOOR);
        if (floored.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) return Long.MAX_VALUE;
        return floored.longValueExact();
    }
}
