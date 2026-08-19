package de.tasticgames.lobby.daily;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A player's daily-reward state: when they last claimed, how long the streak is and what claiming today would
 * do to it. Pure date arithmetic so the rules are testable without a server.
 * <p>
 * The day boundary is a fixed zone (see {@code daily.yml}), not the player's local time: everybody on the
 * network gets a new daily reward at the same moment, which is what makes "be there at reset" work as a
 * reason to come back.
 *
 * @param lastClaim  day of the last claim, or {@code null} when the player never claimed
 * @param streak     days claimed in a row up to {@code lastClaim} (0 without a claim)
 * @param bestStreak longest streak the player ever had
 * @param totalDays  how many daily rewards the player collected in total
 */
public record DailyStreak(LocalDate lastClaim, int streak, int bestStreak, int totalDays) {

    public static final DailyStreak NEVER = new DailyStreak(null, 0, 0, 0);

    public DailyStreak {
        if (streak < 0 || bestStreak < 0 || totalDays < 0) {
            throw new IllegalArgumentException("daily counters cannot be negative");
        }
    }

    /** Whether the reward for {@code today} is still available. */
    public boolean claimable(LocalDate today) {
        Objects.requireNonNull(today, "today");
        return lastClaim == null || lastClaim.isBefore(today);
    }

    /**
     * Streak the player would be on after claiming today: the next day continues the streak, a longer gap
     * starts over at day 1. A claim that already happened today keeps the streak as it is.
     */
    public int streakAfter(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (!claimable(today)) {
            return streak;
        }
        return lastClaim != null && lastClaim.plusDays(1).isEqual(today) ? streak + 1 : 1;
    }

    /** Whether the streak is lost when the player does not claim today (i.e. they were here yesterday). */
    public boolean streakAtRisk(LocalDate today) {
        return streak > 0 && lastClaim != null && lastClaim.plusDays(1).isEqual(Objects.requireNonNull(today));
    }

    /** State after a successful claim on {@code today}. */
    public DailyStreak claim(LocalDate today) {
        if (!claimable(today)) {
            return this;
        }
        int next = streakAfter(today);
        return new DailyStreak(today, next, Math.max(bestStreak, next), totalDays + 1);
    }

    /** Position in the seven day cycle (1..7) the next claim would land on. */
    public int cycleDay(LocalDate today, int cycleLength) {
        if (cycleLength <= 0) {
            throw new IllegalArgumentException("cycleLength must be > 0");
        }
        int next = claimable(today) ? streakAfter(today) : streak;
        return next <= 0 ? 1 : ((next - 1) % cycleLength) + 1;
    }
}
