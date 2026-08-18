package de.tasticgames.lobby.cookie.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A rolled special cookie reward, not yet applied. Instant rewards carry cookies and no buff,
 * buff rewards carry zero cookies and one buff – except {@link GoldenRewardType#BLESSING}, which
 * carries a CPS and a click buff at once.
 *
 * @param rarity  rarity the reward was rolled for
 * @param type    reward type
 * @param cookies instant cookies (zero for buff rewards)
 * @param buffs   buffs to add (empty for instant rewards)
 */
public record SpecialCookieReward(SpecialCookieRarity rarity, GoldenRewardType type, CookieAmount cookies, List<ActiveBuff> buffs) {

    public SpecialCookieReward {
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cookies, "cookies");
        buffs = List.copyOf(Objects.requireNonNull(buffs, "buffs"));
    }

    /** Instant reward: cookies, no buff. */
    public static SpecialCookieReward instant(SpecialCookieRarity rarity, GoldenRewardType type, CookieAmount cookies) {
        return new SpecialCookieReward(rarity, type, cookies, List.of());
    }

    /** Buff reward: no cookies, one or more buffs. */
    public static SpecialCookieReward buffed(SpecialCookieRarity rarity, GoldenRewardType type, ActiveBuff... buffs) {
        return new SpecialCookieReward(rarity, type, CookieAmount.ZERO, List.of(buffs));
    }

    public boolean isInstant() {
        return buffs.isEmpty();
    }

    /** Product of the buff multipliers affecting passive CPS ({@code 1.0} when none does). */
    public double cpsMultiplier() {
        double multiplier = 1.0;
        for (ActiveBuff buff : buffs) {
            if (buff.type().affectsCps()) multiplier *= buff.multiplier();
        }
        return multiplier;
    }

    /** Product of the buff multipliers affecting click power ({@code 1.0} when none does). */
    public double clickMultiplier() {
        double multiplier = 1.0;
        for (ActiveBuff buff : buffs) {
            if (buff.type().affectsClicks()) multiplier *= buff.multiplier();
        }
        return multiplier;
    }

    /** Latest expiry of the carried buffs (empty for instant rewards). */
    public Optional<Instant> buffExpiresAt() {
        Instant latest = null;
        for (ActiveBuff buff : buffs) {
            if (latest == null || buff.expiresAt().isAfter(latest)) latest = buff.expiresAt();
        }
        return Optional.ofNullable(latest);
    }
}
