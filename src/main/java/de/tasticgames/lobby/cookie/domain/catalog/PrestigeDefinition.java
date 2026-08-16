package de.tasticgames.lobby.cookie.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of one prestige level (0..10).
 *
 * @param level                   prestige level
 * @param nameKey                 translation key of the level theme name
 * @param displayName             default English theme name
 * @param requiredLifetimeCookies lifetime cookies required to reach this level (0 for level 0)
 * @param totalMultiplier         global CPS/click multiplier while at this level
 * @param unlockedZoneId          lobby zone unlocked at this level
 * @param unlockedGeneratorIds    generators that become purchasable at this level
 * @param rewardCosmeticIds       cosmetic ids granted once when reaching this level
 */
public record PrestigeDefinition(
        int level,
        String nameKey,
        String displayName,
        BigDecimal requiredLifetimeCookies,
        double totalMultiplier,
        String unlockedZoneId,
        List<String> unlockedGeneratorIds,
        List<String> rewardCosmeticIds
) {

    public PrestigeDefinition {
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(requiredLifetimeCookies, "requiredLifetimeCookies");
        Objects.requireNonNull(unlockedZoneId, "unlockedZoneId");
        unlockedGeneratorIds = List.copyOf(Objects.requireNonNull(unlockedGeneratorIds, "unlockedGeneratorIds"));
        rewardCosmeticIds = List.copyOf(Objects.requireNonNull(rewardCosmeticIds, "rewardCosmeticIds"));
    }

    public PrestigeDefinition withUnlockedGenerators(List<String> generatorIds) {
        return new PrestigeDefinition(level, nameKey, displayName, requiredLifetimeCookies, totalMultiplier,
                unlockedZoneId, generatorIds, rewardCosmeticIds);
    }
}
