package de.tasticgames.lobby.cookie.domain.format;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieNumberFormatterTest {

    private final CookieNumberFormatter formatter = new CookieNumberFormatter(Locale.ENGLISH);

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "1, 1",
            "999, 999",
            "1234, '1,234'",
            "9999, '9,999'",
            "9999.9, '9,999'",
            "10000, 10.00K",
            "12345, 12.34K",
            "999999, 999.99K",
            "1000000, 1.00M",
            "1234567, 1.23M",
            "1500000000, 1.50B",
            "1e12, 1.00T",
            "1e15, 1.00Qa",
            "1e18, 1.00Qi",
            "1e21, 1.00Sx",
            "1e24, 1.00Sp",
            "1e27, 1.00Oc",
            "1e30, 1.00No",
            "1e33, 1.00Dc",
            "1e36, 1.00UDc",
            "1e39, 1.00DDc",
            "1e42, 1.00TDc",
            "1e63, 1.00Vg",
            "9.99e65, 999.00Vg",
            "9.9999e65, 999.99Vg",
            "1e66, 1.00e66",
            "1.23456e70, 1.23e70",
    })
    void formatsEnglish(String input, String expected) {
        assertEquals(expected, formatter.format(new BigDecimal(input), Locale.ENGLISH));
    }

    @Test
    void formatsGermanGroupingAndDecimalSeparator() {
        assertEquals("1.234", formatter.format(new BigDecimal("1234"), Locale.GERMAN));
        assertEquals("12,34K", formatter.format(new BigDecimal("12345"), Locale.GERMAN));
        assertEquals("1,00M", formatter.format(new BigDecimal("1000000"), Locale.GERMAN));
    }

    @Test
    void formatsHindiWithoutThrowing() {
        Locale hindi = Locale.forLanguageTag("hi");
        String s = formatter.format(new BigDecimal("1234567"), hindi);
        assertTrue(s.endsWith("M"), s);
        assertFalse(formatter.format(new BigDecimal("1234"), hindi).isEmpty());
    }

    @Test
    void usesDefaultLocaleAndAmountOverloads() {
        assertEquals("1.23M", formatter.format(CookieAmount.of("1234567")));
        assertEquals("1,234,567", formatter.formatFull(CookieAmount.of("1234567"), Locale.ENGLISH));
        assertEquals("0.5", formatter.formatRate(new BigDecimal("0.55"), Locale.ENGLISH));
        assertEquals("12.34K", formatter.formatRate(new BigDecimal("12345"), Locale.ENGLISH));
    }

    @Test
    void mantissaIsTruncatedNotRounded() {
        assertEquals("999.99K", formatter.format(new BigDecimal("999999.99"), Locale.ENGLISH));
        assertEquals("1.99M", formatter.format(new BigDecimal("1999999"), Locale.ENGLISH));
    }
}
