package de.tasticgames.lobby.cookie.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A temporary multiplier. Transient (not persisted).
 *
 * @param type       what the buff multiplies
 * @param multiplier multiplier (≥ 0)
 * @param startsAt   start instant (inclusive)
 * @param expiresAt  end instant (exclusive)
 * @param source     free-form source id, e.g. {@code "golden:FRENZY"}
 */
public record ActiveBuff(BuffType type, double multiplier, Instant startsAt, Instant expiresAt, String source) {

    public ActiveBuff {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(source, "source");
        if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 0) {
            throw new IllegalArgumentException("Buff multiplier must be finite and >= 0");
        }
        if (expiresAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("Buff expiresAt must not be before startsAt");
        }
    }

    public static ActiveBuff of(BuffType type, double multiplier, Instant startsAt, Duration duration, String source) {
        return new ActiveBuff(type, multiplier, startsAt, startsAt.plus(duration), source);
    }

    /** Active if {@code startsAt <= now < expiresAt}. */
    public boolean isActiveAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Duration remainingAt(Instant now) {
        Duration d = Duration.between(now, expiresAt);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
