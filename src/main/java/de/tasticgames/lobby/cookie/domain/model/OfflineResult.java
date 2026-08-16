package de.tasticgames.lobby.cookie.domain.model;

/**
 * Offline production result.
 *
 * @param seconds       raw seconds away (after excluding already claimed windows)
 * @param cappedSeconds seconds actually credited (≤ balancing cap)
 * @param efficiency    efficiency fraction applied
 * @param cookies       cookies credited
 */
public record OfflineResult(long seconds, long cappedSeconds, double efficiency, CookieAmount cookies) {

    public static final OfflineResult NONE = new OfflineResult(0, 0, 0, CookieAmount.ZERO);

    public boolean isEmpty() {
        return cookies.isZero();
    }
}
