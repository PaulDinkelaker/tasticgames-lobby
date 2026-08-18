package de.tasticgames.lobby.cookie.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A special cookie that became due: the drawn rarity and the window it stays clickable in
 * {@code spawn} mode. In {@code auto} mode the reward is applied at {@code scheduledFor} and
 * {@code expiresAt} is unused.
 *
 * @param rarity       drawn rarity
 * @param scheduledFor instant the cookie was scheduled for (and drawn at)
 * @param expiresAt    instant a spawned cookie disappears again
 */
public record SpecialCookieRoll(SpecialCookieRarity rarity, Instant scheduledFor, Instant expiresAt) {

    public SpecialCookieRoll {
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.isBefore(scheduledFor)) {
            throw new IllegalArgumentException("expiresAt must not be before scheduledFor");
        }
    }
}
