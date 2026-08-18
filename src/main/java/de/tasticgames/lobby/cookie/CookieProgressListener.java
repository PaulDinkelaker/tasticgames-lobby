package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;

import java.util.List;
import java.util.UUID;

/**
 * Progress the Cookie Clicker reports to the rest of the lobby (today: the season pass, which turns
 * it into pass XP and quest metrics). Implementations are called from the cookie services on the
 * thread the progress happened on and must never block or throw – the cookie game does not depend
 * on them. {@link #NONE} is the default, so the cookie module works without a consumer.
 */
public interface CookieProgressListener {

    CookieProgressListener NONE = new CookieProgressListener() {
    };

    /** One hand-baked click; {@code baked} is the reward of that click. */
    default void onClick(UUID player, CookieAmount baked) {
    }

    /** {@code count} units of a generator were bought. */
    default void onGeneratorsBought(UUID player, String generatorId, int count) {
    }

    default void onUpgradeBought(UUID player, String upgradeId) {
    }

    /** A prestige was applied by the API; {@code level} is the new prestige level. */
    default void onPrestige(UUID player, int level) {
    }

    /** A special cookie of the given rarity was activated (auto mode) or clicked. */
    default void onSpecialCookie(UUID player, SpecialCookieRarity rarity) {
    }

    default void onZoneDiscovered(UUID player, String zoneId) {
    }

    default void onNpcQuestCompleted(UUID player, String questId) {
    }

    /** Cookie achievements unlocked by the last action (never empty when called). */
    default void onAchievementsUnlocked(UUID player, List<String> achievementIds) {
    }
}
