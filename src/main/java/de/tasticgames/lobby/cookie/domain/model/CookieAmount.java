package de.tasticgames.lobby.cookie.domain.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable, non-negative cookie amount backed by {@link BigDecimal}.
 * <p>
 * Values are stored with a fixed scale of {@value #SCALE} decimal places, rounded
 * {@link RoundingMode#HALF_DOWN}. All arithmetic returns new instances. Never use
 * {@code double} as a source of truth; {@link #toDouble()} exists purely for display.
 */
public final class CookieAmount implements Comparable<CookieAmount> {

    /** Storage scale (decimal places). */
    public static final int SCALE = 10;
    /** Storage rounding mode. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_DOWN;
    /** Math context used for multiplications/divisions with fractional operands. */
    public static final MathContext MC = MathContext.DECIMAL128;

    public static final CookieAmount ZERO = new CookieAmount(BigDecimal.ZERO);
    public static final CookieAmount ONE = new CookieAmount(BigDecimal.ONE);

    private final BigDecimal value;

    private CookieAmount(BigDecimal raw) {
        Objects.requireNonNull(raw, "value");
        BigDecimal scaled = raw.setScale(SCALE, ROUNDING);
        if (scaled.signum() < 0) {
            throw new IllegalArgumentException("CookieAmount must be non-negative, was " + raw.toPlainString());
        }
        this.value = scaled;
    }

    // ---------------------------------------------------------------- factories

    public static CookieAmount of(long cookies) {
        return new CookieAmount(BigDecimal.valueOf(cookies));
    }

    public static CookieAmount of(String decimal) {
        return parse(decimal);
    }

    public static CookieAmount of(BigDecimal decimal) {
        return new CookieAmount(decimal);
    }

    public static CookieAmount of(BigInteger integer) {
        return new CookieAmount(new BigDecimal(integer));
    }

    /**
     * Parses a decimal string (plain or scientific notation, e.g. {@code "1234.5"} or {@code "1e36"}).
     *
     * @throws IllegalArgumentException if the string is not a valid non-negative decimal
     */
    public static CookieAmount parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim().replace("_", "");
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty cookie amount");
        }
        try {
            return new CookieAmount(new BigDecimal(trimmed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cookie amount: '" + text + "'", e);
        }
    }

    /**
     * Converts a display double (e.g. a multiplier) into an amount. Only use for values that
     * originate from configuration; never for stored balances.
     */
    public static CookieAmount ofDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Cannot convert " + value + " to CookieAmount");
        }
        return new CookieAmount(BigDecimal.valueOf(value));
    }

    // ---------------------------------------------------------------- arithmetic

    public CookieAmount plus(CookieAmount other) {
        return new CookieAmount(value.add(other.value));
    }

    public CookieAmount plus(BigDecimal other) {
        return new CookieAmount(value.add(other));
    }

    /**
     * Subtracts {@code other}.
     *
     * @throws ArithmeticException if the result would be negative
     */
    public CookieAmount minus(CookieAmount other) {
        BigDecimal result = value.subtract(other.value);
        if (result.signum() < 0) {
            throw new ArithmeticException("Insufficient cookies: " + toPlainString() + " - " + other.toPlainString());
        }
        return new CookieAmount(result);
    }

    /** Subtracts {@code other}, clamping the result at zero. */
    public CookieAmount minusClamped(CookieAmount other) {
        BigDecimal result = value.subtract(other.value);
        return result.signum() < 0 ? ZERO : new CookieAmount(result);
    }

    public CookieAmount times(long factor) {
        if (factor < 0) throw new IllegalArgumentException("factor must be non-negative");
        return new CookieAmount(value.multiply(BigDecimal.valueOf(factor)));
    }

    public CookieAmount times(BigDecimal factor) {
        return new CookieAmount(value.multiply(factor, MC));
    }

    /** Multiplies with a configuration double (converted via {@link BigDecimal#valueOf(double)}). */
    public CookieAmount times(double factor) {
        if (Double.isNaN(factor) || Double.isInfinite(factor)) {
            throw new IllegalArgumentException("Invalid factor " + factor);
        }
        return new CookieAmount(value.multiply(BigDecimal.valueOf(factor), MC));
    }

    public CookieAmount dividedBy(BigDecimal divisor) {
        return new CookieAmount(value.divide(divisor, MC));
    }

    public CookieAmount dividedBy(long divisor) {
        return dividedBy(BigDecimal.valueOf(divisor));
    }

    /** Rounds up to the next whole cookie. */
    public CookieAmount ceil() {
        return new CookieAmount(value.setScale(0, RoundingMode.CEILING));
    }

    /** Rounds down to the previous whole cookie. */
    public CookieAmount floor() {
        return new CookieAmount(value.setScale(0, RoundingMode.FLOOR));
    }

    public CookieAmount max(CookieAmount other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public CookieAmount min(CookieAmount other) {
        return compareTo(other) <= 0 ? this : other;
    }

    // ---------------------------------------------------------------- queries

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isGreaterThan(CookieAmount other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(CookieAmount other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(CookieAmount other) {
        return compareTo(other) < 0;
    }

    public BigDecimal toBigDecimal() {
        return value;
    }

    /** Whole-cookie part as BigInteger (floor). */
    public BigInteger toBigInteger() {
        return value.toBigInteger();
    }

    /** Display-only conversion; may lose precision or overflow to infinity for huge values. */
    public double toDouble() {
        return value.doubleValue();
    }

    /** Plain decimal string without exponent and without trailing zeros (e.g. {@code "1234.5"}). */
    public String toPlainString() {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    /** Full-precision storage string (scale {@value #SCALE}). */
    public String toStorageString() {
        return value.toPlainString();
    }

    @Override
    public int compareTo(CookieAmount o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CookieAmount that)) return false;
        return value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
