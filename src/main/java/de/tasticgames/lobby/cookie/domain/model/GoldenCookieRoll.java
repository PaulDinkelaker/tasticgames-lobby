package de.tasticgames.lobby.cookie.domain.model;

import java.time.Instant;

/**
 * Result of a golden cookie spawn roll.
 *
 * @param spawned     whether a golden cookie spawns
 * @param probability probability used for this roll
 * @param rolledAt    roll instant
 * @param expiresAt   instant at which the spawned cookie disappears (null if not spawned)
 */
public record GoldenCookieRoll(boolean spawned, double probability, Instant rolledAt, Instant expiresAt) {
}
