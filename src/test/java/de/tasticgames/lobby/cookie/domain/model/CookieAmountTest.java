package de.tasticgames.lobby.cookie.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieAmountTest {

    @Test
    void factoriesAndParsing() {
        assertEquals("15", CookieAmount.of(15).toPlainString());
        assertEquals("1234.5", CookieAmount.of("1234.5").toPlainString());
        assertEquals("1000000", CookieAmount.parse("1e6").toPlainString());
        assertEquals("1000000", CookieAmount.parse(" 1_000_000 ").toPlainString());
        assertEquals("0", CookieAmount.ZERO.toPlainString());
        assertEquals(CookieAmount.of(3), CookieAmount.of(new BigDecimal("3.00")));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> CookieAmount.of(-1));
        assertThrows(IllegalArgumentException.class, () -> CookieAmount.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> CookieAmount.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CookieAmount.of("-0.5"));
        assertThrows(IllegalArgumentException.class, () -> CookieAmount.ofDouble(Double.NaN));
    }

    @Test
    void storageScaleIsTenWithHalfDown() {
        CookieAmount a = CookieAmount.of("1.00000000005"); // 11 decimals, exactly half → HALF_DOWN rounds down
        assertEquals("1.0000000000", a.toStorageString());
        CookieAmount b = CookieAmount.of("1.00000000006");
        assertEquals("1.0000000001", b.toStorageString());
        assertEquals(10, a.toBigDecimal().scale());
    }

    @Test
    void arithmetic() {
        CookieAmount a = CookieAmount.of(100);
        CookieAmount b = CookieAmount.of("2.5");
        assertEquals("102.5", a.plus(b).toPlainString());
        assertEquals("97.5", a.minus(b).toPlainString());
        assertEquals("250", a.times(b.toBigDecimal()).toPlainString());
        assertEquals("300", a.times(3).toPlainString());
        assertEquals("115", a.times(1.15).toPlainString());
        assertEquals("40", a.dividedBy(new BigDecimal("2.5")).toPlainString());
        assertEquals("3", CookieAmount.of("2.1").ceil().toPlainString());
        assertEquals("2", CookieAmount.of("2.9").floor().toPlainString());
        assertEquals("0", CookieAmount.of(1).minusClamped(CookieAmount.of(5)).toPlainString());
        assertThrows(ArithmeticException.class, () -> CookieAmount.of(1).minus(CookieAmount.of(2)));
        assertThrows(IllegalArgumentException.class, () -> a.times(-1L));
    }

    @Test
    void comparisonAndPredicates() {
        assertTrue(CookieAmount.ZERO.isZero());
        assertFalse(CookieAmount.ONE.isZero());
        assertTrue(CookieAmount.ONE.isPositive());
        assertTrue(CookieAmount.of(2).isGreaterThan(CookieAmount.ONE));
        assertTrue(CookieAmount.of(2).isGreaterThanOrEqual(CookieAmount.of(2)));
        assertTrue(CookieAmount.ONE.isLessThan(CookieAmount.of(2)));
        assertEquals(0, CookieAmount.of("1.0").compareTo(CookieAmount.of(1)));
        assertEquals(CookieAmount.of(5), CookieAmount.of(3).max(CookieAmount.of(5)));
        assertEquals(CookieAmount.of(3), CookieAmount.of(3).min(CookieAmount.of(5)));
    }

    @Test
    void equalsAndHashCodeIgnoreRepresentation() {
        CookieAmount a = CookieAmount.of("1e3");
        CookieAmount b = CookieAmount.of(1000);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, CookieAmount.of(1001));
    }

    @Test
    void hugeValuesStayExact() {
        CookieAmount huge = CookieAmount.of("1e40");
        assertEquals("10000000000000000000000000000000000000000", huge.toPlainString());
        assertEquals("10000000000000000000000000000000000000001", huge.plus(CookieAmount.ONE).toPlainString());
        assertTrue(huge.toDouble() > 9e39);
        assertEquals("1", CookieAmount.of("1e40").dividedBy(new BigDecimal("1e40")).toPlainString());
    }
}
