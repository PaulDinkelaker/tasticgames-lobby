package de.tasticgames.lobby.cookie.domain.model;

import java.math.BigDecimal;

/**
 * Per-generator CPS contribution (all multipliers applied, including buffs).
 *
 * @param generatorId generator id
 * @param count       owned units
 * @param cpsEach     effective CPS of one unit
 * @param cpsTotal    effective CPS of all units ({@code count * cpsEach})
 */
public record Contribution(String generatorId, int count, BigDecimal cpsEach, BigDecimal cpsTotal) {
}
