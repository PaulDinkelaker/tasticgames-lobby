package de.tasticgames.lobby.cookie.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of a {@link CookieProfile}, safe to hand to other threads (persistence,
 * leaderboards, achievement evaluation).
 */
public record CookieProfileSnapshot(
        UUID playerId,
        CookieAmount cookies,
        CookieAmount lifetimeCookies,
        int prestigeLevel,
        long crumbs,
        long crumbsEarnedTotal,
        long totalClicks,
        long goldenCookiesClicked,
        long playtimeSeconds,
        int highestCombo,
        Map<String, Integer> generators,
        Set<String> upgrades,
        Map<String, Integer> prestigeUpgrades,
        Set<String> achievements,
        Set<String> discoveredZones,
        Instant lastActiveAt,
        Instant offlineClaimedUntil,
        long version,
        List<ActiveBuff> activeBuffs,
        int comboStage
) {

    public CookieProfileSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cookies, "cookies");
        Objects.requireNonNull(lifetimeCookies, "lifetimeCookies");
        generators = Map.copyOf(Objects.requireNonNull(generators, "generators"));
        upgrades = Set.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        prestigeUpgrades = Map.copyOf(Objects.requireNonNull(prestigeUpgrades, "prestigeUpgrades"));
        achievements = Set.copyOf(Objects.requireNonNull(achievements, "achievements"));
        discoveredZones = Set.copyOf(Objects.requireNonNull(discoveredZones, "discoveredZones"));
        activeBuffs = List.copyOf(Objects.requireNonNull(activeBuffs, "activeBuffs"));
    }

    public int generatorCount(String generatorId) {
        return generators.getOrDefault(generatorId, 0);
    }

    public long totalGenerators() {
        long total = 0;
        for (int v : generators.values()) total += v;
        return total;
    }

    public boolean hasUpgrade(String id) {
        return upgrades.contains(id);
    }

    public int prestigeUpgradeLevel(String nodeId) {
        return prestigeUpgrades.getOrDefault(nodeId, 0);
    }
}
