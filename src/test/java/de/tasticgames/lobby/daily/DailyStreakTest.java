package de.tasticgames.lobby.daily;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The streak rules: one claim a day, the next day continues, a gap starts over. */
class DailyStreakTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
    private static final LocalDate NEXT_WEEK = MONDAY.plusDays(7);

    @Test
    void aNewPlayerCanClaimAndStartsAtDayOne() {
        DailyStreak fresh = DailyStreak.NEVER;
        assertTrue(fresh.claimable(MONDAY));
        assertEquals(1, fresh.streakAfter(MONDAY));
        assertEquals(1, fresh.cycleDay(MONDAY, 7));

        DailyStreak claimed = fresh.claim(MONDAY);
        assertEquals(MONDAY, claimed.lastClaim());
        assertEquals(1, claimed.streak());
        assertEquals(1, claimed.bestStreak());
        assertEquals(1, claimed.totalDays());
    }

    @Test
    void theSecondClaimOnTheSameDayChangesNothing() {
        DailyStreak claimed = DailyStreak.NEVER.claim(MONDAY);
        assertFalse(claimed.claimable(MONDAY));
        assertEquals(claimed, claimed.claim(MONDAY));
        assertEquals(1, claimed.streakAfter(MONDAY), "the streak does not grow twice a day");
    }

    @Test
    void claimingOnTheNextDayContinuesTheStreak() {
        DailyStreak streak = DailyStreak.NEVER.claim(MONDAY).claim(TUESDAY).claim(WEDNESDAY);
        assertEquals(3, streak.streak());
        assertEquals(3, streak.bestStreak());
        assertEquals(3, streak.totalDays());
        assertEquals(4, streak.cycleDay(WEDNESDAY.plusDays(1), 7), "day four of the cycle comes next");
    }

    @Test
    void aMissedDayStartsOverButKeepsTheRecord() {
        DailyStreak streak = DailyStreak.NEVER.claim(MONDAY).claim(TUESDAY).claim(WEDNESDAY);
        DailyStreak afterGap = streak.claim(NEXT_WEEK);
        assertEquals(1, afterGap.streak(), "the streak restarts");
        assertEquals(3, afterGap.bestStreak(), "the record stays");
        assertEquals(4, afterGap.totalDays(), "every claim counts towards the total");
        assertEquals(1, afterGap.cycleDay(NEXT_WEEK, 7));
    }

    @Test
    void theCycleWrapsWhileTheStreakKeepsGrowing() {
        DailyStreak streak = DailyStreak.NEVER;
        LocalDate day = MONDAY;
        for (int i = 0; i < 7; i++) {
            streak = streak.claim(day);
            day = day.plusDays(1);
        }
        assertEquals(7, streak.streak());
        assertEquals(1, streak.cycleDay(day, 7), "the eighth claim starts the cycle over at day one");

        streak = streak.claim(day); // the eighth day in a row
        day = day.plusDays(1);
        assertEquals(8, streak.streak(), "the streak itself keeps counting");
        assertEquals(2, streak.cycleDay(day, 7));
        assertEquals(9, streak.streakAfter(day));
    }

    @Test
    void theStreakIsAtRiskOnlyOnTheDayAfterAClaim() {
        DailyStreak streak = DailyStreak.NEVER.claim(MONDAY);
        assertTrue(streak.streakAtRisk(TUESDAY));
        assertFalse(streak.streakAtRisk(MONDAY), "claiming today means nothing is at risk");
        assertFalse(streak.streakAtRisk(WEDNESDAY), "the streak is already gone by then");
    }
}
