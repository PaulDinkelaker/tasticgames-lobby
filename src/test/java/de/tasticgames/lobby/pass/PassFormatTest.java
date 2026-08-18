package de.tasticgames.lobby.pass;

import de.tasticgames.localization.SupportedLanguage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassFormatTest {

    @Test
    void progressBarFillsProportionally() {
        assertEquals("░░░░░░░░░░", PassFormat.progressBar(0, 100, 10));
        assertEquals("█████░░░░░", PassFormat.progressBar(50, 100, 10));
        assertEquals("██████████", PassFormat.progressBar(100, 100, 10));
        assertEquals(PassFormat.BAR_WIDTH, PassFormat.progressBar(3, 7).length());
    }

    @Test
    void barAndPercentAreClampedAndTreatMaxLevelAsComplete() {
        assertEquals("██████████", PassFormat.progressBar(500, 100, 10), "overshooting XP never overflows the bar");
        assertEquals("██████████", PassFormat.progressBar(0, 0, 10), "no XP required means the level is done");
        assertEquals("░░░░░░░░░░", PassFormat.progressBar(-10, 100, 10));
        assertEquals(100, PassFormat.percent(0, 0));
        assertEquals(100, PassFormat.percent(999, 100));
        assertEquals(0, PassFormat.percent(-5, 100));
        assertEquals(33, PassFormat.percent(1, 3));
    }

    @Test
    void priceUsesThePlayersLocale() {
        String german = PassFormat.price(999, "EUR", SupportedLanguage.GERMAN);
        assertTrue(german.contains("9,99"), german);
        assertTrue(german.contains("€"), german);
        String english = PassFormat.price(999, "EUR", SupportedLanguage.ENGLISH);
        assertTrue(english.contains("9.99"), english);
        String unknown = PassFormat.price(999, "XyZ", SupportedLanguage.ENGLISH);
        assertTrue(unknown.contains("9.99"), unknown);
    }

    @Test
    void boostValuesAreReadable() {
        assertEquals(1.25, PassFormat.multiplier(125), 1e-9);
        assertEquals(1.0, PassFormat.multiplier(0), 1e-9);
        assertEquals("3d", PassFormat.duration("P3D"));
        assertEquals("7d", PassFormat.duration("P7D"));
        assertEquals("12h", PassFormat.duration("PT12H"));
        assertEquals("30m", PassFormat.duration("PT30M"));
        assertEquals("", PassFormat.duration(null));
        assertEquals("soon", PassFormat.duration("soon"), "unparsable values are shown as they are");
    }

    @Test
    void daysLeftNeverGoesNegative() {
        Instant now = Instant.parse("2026-08-18T12:00:00Z");
        assertEquals(9, PassFormat.daysLeft(now.plus(9, ChronoUnit.DAYS), now));
        assertEquals(0, PassFormat.daysLeft(now.minus(1, ChronoUnit.DAYS), now));
        assertEquals(0, PassFormat.daysLeft(null, now));
    }

    @Test
    void numbersAreGrouped() {
        assertEquals("173.750", PassFormat.number(173_750, SupportedLanguage.GERMAN));
        assertEquals("173,750", PassFormat.number(173_750, SupportedLanguage.ENGLISH));
    }
}
