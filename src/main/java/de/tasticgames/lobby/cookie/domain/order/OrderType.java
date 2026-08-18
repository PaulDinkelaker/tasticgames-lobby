package de.tasticgames.lobby.cookie.domain.order;

/**
 * What a shift order asks for. Every type is measured while the player is online: the board lives in the
 * session, so an order is a short goal for the current shift and never a multi-day task.
 */
public enum OrderType {
    /** Hand-baked clicks. */
    CLICKS,
    /** Cookies baked (clicks and production together). */
    COOKIES,
    /** Generators bought. */
    GENERATORS,
    /** Upgrades bought. */
    UPGRADES,
    /** Special cookies collected. */
    SPECIALS
}
