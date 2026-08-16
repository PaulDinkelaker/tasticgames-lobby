package de.tasticgames.lobby.cookie.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Description of what a prestige would do. Produced without mutating the profile.
 *
 * @param eligible             whether the prestige can be applied
 * @param fromLevel            current level
 * @param toLevel              level after the prestige (equals fromLevel if not eligible)
 * @param crumbsGained         crumbs that would be gained
 * @param crumbsAfter          crumbs after the prestige
 * @param newMultiplier        prestige multiplier of {@code toLevel}
 * @param rewardCosmeticIds    cosmetics granted
 * @param unlockedZoneId       zone unlocked (may be null when not eligible)
 * @param unlockedGeneratorIds generators unlocked
 * @param startingCookies      bank after reset
 * @param startingGenerators   generators after reset
 * @param keeps                human readable list of what is kept
 * @param resets               human readable list of what is reset
 */
public record PrestigePlan(
        boolean eligible,
        int fromLevel,
        int toLevel,
        long crumbsGained,
        long crumbsAfter,
        double newMultiplier,
        List<String> rewardCosmeticIds,
        String unlockedZoneId,
        List<String> unlockedGeneratorIds,
        CookieAmount startingCookies,
        Map<String, Integer> startingGenerators,
        List<String> keeps,
        List<String> resets
) {

    public static final List<String> KEEPS = List.of(
            "lifetimeCookies", "crumbs", "crumbsEarnedTotal", "prestigeUpgrades", "achievements",
            "discoveredZones", "totalClicks", "goldenCookiesClicked", "playtimeSeconds", "highestCombo");
    public static final List<String> RESETS = List.of(
            "cookies", "generators", "upgrades", "activeBuffs", "combo");

    public PrestigePlan {
        rewardCosmeticIds = List.copyOf(Objects.requireNonNull(rewardCosmeticIds, "rewardCosmeticIds"));
        unlockedGeneratorIds = List.copyOf(Objects.requireNonNull(unlockedGeneratorIds, "unlockedGeneratorIds"));
        Objects.requireNonNull(startingCookies, "startingCookies");
        startingGenerators = Map.copyOf(Objects.requireNonNull(startingGenerators, "startingGenerators"));
        keeps = List.copyOf(Objects.requireNonNull(keeps, "keeps"));
        resets = List.copyOf(Objects.requireNonNull(resets, "resets"));
    }
}
