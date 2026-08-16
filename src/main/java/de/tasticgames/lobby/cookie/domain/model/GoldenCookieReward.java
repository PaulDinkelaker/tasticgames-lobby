package de.tasticgames.lobby.cookie.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A rolled golden cookie reward, not yet applied.
 *
 * @param type    reward type
 * @param cookies instant cookies (zero for buff rewards)
 * @param buff    buff to add (null for instant rewards)
 */
public record GoldenCookieReward(GoldenRewardType type, CookieAmount cookies, ActiveBuff buff) {

    public GoldenCookieReward {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cookies, "cookies");
    }

    public Optional<ActiveBuff> buffOptional() {
        return Optional.ofNullable(buff);
    }

    public boolean isInstant() {
        return buff == null;
    }
}
