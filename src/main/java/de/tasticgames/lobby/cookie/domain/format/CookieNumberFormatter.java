package de.tasticgames.lobby.cookie.domain.format;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Human-readable, locale-aware formatting of cookie amounts.
 * <ul>
 *   <li>{@code 0 .. 9,999} → grouped integer ({@code 1,234})</li>
 *   <li>{@code 10,000 .. <10^66} → mantissa with two fraction digits (rounded down) plus a
 *       short-scale suffix: K, M, B, T, Qa, Qi, Sx, Sp, Oc, No, Dc, UDc, DDc, TDc, QaDc, QiDc,
 *       SxDc, SpDc, OcDc, NoDc, Vg</li>
 *   <li>{@code ≥ 10^66} → scientific notation ({@code 1.23e66})</li>
 * </ul>
 * All formatting is display-only; the value model stays {@link BigDecimal}.
 */
public final class CookieNumberFormatter {

    /** Suffixes by exponent group (index i means 10^(3i)). Index 0 = no suffix. */
    public static final List<String> SUFFIXES = List.of(
            "", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc",
            "UDc", "DDc", "TDc", "QaDc", "QiDc", "SxDc", "SpDc", "OcDc", "NoDc", "Vg");

    /** Amounts below this threshold are shown as full grouped integers. */
    public static final BigDecimal FULL_NUMBER_LIMIT = BigDecimal.valueOf(10_000);

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final Locale defaultLocale;

    public CookieNumberFormatter() {
        this(Locale.ENGLISH);
    }

    public CookieNumberFormatter(Locale defaultLocale) {
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    public String format(CookieAmount amount) {
        return format(amount.toBigDecimal(), defaultLocale);
    }

    public String format(CookieAmount amount, Locale locale) {
        return format(amount.toBigDecimal(), locale);
    }

    public String format(BigDecimal value, Locale locale) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(locale, "locale");
        if (value.signum() < 0) {
            return "-" + format(value.negate(), locale);
        }
        if (value.compareTo(FULL_NUMBER_LIMIT) < 0) {
            NumberFormat integerFormat = NumberFormat.getIntegerInstance(locale);
            integerFormat.setRoundingMode(RoundingMode.FLOOR);
            integerFormat.setGroupingUsed(true);
            return integerFormat.format(value.setScale(0, RoundingMode.FLOOR).longValueExact());
        }
        BigInteger whole = value.setScale(0, RoundingMode.FLOOR).toBigInteger();
        int digits = whole.toString().length();               // ≥ 5 here
        int group = (digits - 1) / 3;                          // 10^(3*group) ≤ value
        if (group >= SUFFIXES.size()) {
            return scientific(value, locale);
        }
        BigDecimal divisor = THOUSAND.pow(group);
        BigDecimal mantissa = value.divide(divisor, 2, RoundingMode.DOWN);
        return decimalFormat(locale).format(mantissa) + SUFFIXES.get(group);
    }

    /** Formats as full grouped integer regardless of magnitude (e.g. tooltips). */
    public String formatFull(CookieAmount amount, Locale locale) {
        BigInteger whole = amount.toBigInteger();
        NumberFormat integerFormat = NumberFormat.getIntegerInstance(locale);
        integerFormat.setGroupingUsed(true);
        return integerFormat.format(whole);
    }

    /** Formats a CPS-like rate: values below 10 keep one decimal, otherwise like {@link #format}. */
    public String formatRate(BigDecimal value, Locale locale) {
        if (value.compareTo(BigDecimal.TEN) < 0) {
            NumberFormat nf = NumberFormat.getNumberInstance(locale);
            nf.setMinimumFractionDigits(1);
            nf.setMaximumFractionDigits(1);
            nf.setRoundingMode(RoundingMode.DOWN);
            return nf.format(value);
        }
        return format(value, locale);
    }

    private String scientific(BigDecimal value, Locale locale) {
        BigDecimal stripped = value.stripTrailingZeros();
        int exponent = stripped.precision() - stripped.scale() - 1;
        BigDecimal mantissa = stripped.movePointLeft(exponent).setScale(2, RoundingMode.DOWN);
        return decimalFormat(locale).format(mantissa) + "e" + exponent;
    }

    private static NumberFormat decimalFormat(Locale locale) {
        NumberFormat nf = NumberFormat.getNumberInstance(locale);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        nf.setRoundingMode(RoundingMode.DOWN);
        nf.setGroupingUsed(false);
        return nf;
    }
}
