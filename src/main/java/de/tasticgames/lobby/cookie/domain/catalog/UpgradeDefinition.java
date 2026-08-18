package de.tasticgames.lobby.cookie.domain.catalog;

import de.tasticgames.lobby.cookie.domain.model.CookieAmount;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable definition of a one-time upgrade.
 *
 * @param id                  stable snake_case id
 * @param nameKey             translation key for the display name
 * @param cost                price in cookies
 * @param effect              effect
 * @param requiredGeneratorId generator that must be owned {@code requiredCount} times, or {@code null}
 * @param requiredCount       required owned count of {@code requiredGeneratorId} (ignored if id is null)
 * @param unlockPrestige      minimum prestige level to see/buy this upgrade
 * @param exclusiveGroup      group of upgrades of which only one can be owned per run, or {@code null}
 */
public record UpgradeDefinition(
        String id,
        String nameKey,
        CookieAmount cost,
        UpgradeEffect effect,
        String requiredGeneratorId,
        int requiredCount,
        int unlockPrestige,
        String exclusiveGroup
) {

    public UpgradeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(effect, "effect");
        if (requiredGeneratorId != null && requiredGeneratorId.isBlank()) {
            requiredGeneratorId = null;
        }
        if (exclusiveGroup != null && exclusiveGroup.isBlank()) {
            exclusiveGroup = null;
        }
    }

    public static UpgradeDefinition of(String id, long cost, UpgradeEffect effect) {
        return new UpgradeDefinition(id, "cookie.upgrade." + id, CookieAmount.of(cost), effect, null, 0, 0, null);
    }

    public static UpgradeDefinition of(String id, String cost, UpgradeEffect effect) {
        return new UpgradeDefinition(id, "cookie.upgrade." + id, CookieAmount.of(cost), effect, null, 0, 0, null);
    }

    public UpgradeDefinition requiring(String generatorId, int count) {
        return new UpgradeDefinition(id, nameKey, cost, effect, generatorId, count, unlockPrestige, exclusiveGroup);
    }

    public UpgradeDefinition unlockedAtPrestige(int prestige) {
        return new UpgradeDefinition(id, nameKey, cost, effect, requiredGeneratorId, requiredCount, prestige, exclusiveGroup);
    }

    /** Only one upgrade of a group can be owned at a time – a prestige clears the choice with the upgrades. */
    public UpgradeDefinition exclusiveIn(String group) {
        return new UpgradeDefinition(id, nameKey, cost, effect, requiredGeneratorId, requiredCount, unlockPrestige, group);
    }

    public UpgradeDefinition withCost(long newCost) {
        return new UpgradeDefinition(id, nameKey, CookieAmount.of(newCost), effect, requiredGeneratorId, requiredCount, unlockPrestige, exclusiveGroup);
    }

    public UpgradeDefinition withEffect(UpgradeEffect newEffect) {
        return new UpgradeDefinition(id, nameKey, cost, newEffect, requiredGeneratorId, requiredCount, unlockPrestige, exclusiveGroup);
    }

    public Optional<String> requiredGenerator() {
        return Optional.ofNullable(requiredGeneratorId);
    }

    public boolean isExclusive() {
        return exclusiveGroup != null;
    }

    public boolean hasGeneratorRequirement() {
        return requiredGeneratorId != null && requiredCount > 0;
    }
}
