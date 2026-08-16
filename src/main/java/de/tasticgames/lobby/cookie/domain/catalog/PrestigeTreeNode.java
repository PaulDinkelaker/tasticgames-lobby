package de.tasticgames.lobby.cookie.domain.catalog;

import java.util.Objects;

/**
 * Immutable prestige tree node ("heavenly upgrade") bought with crumbs.
 * Cost of level {@code n → n+1} is {@code baseCost * 2^n}.
 *
 * @param id                stable id
 * @param nameKey           translation key
 * @param maxLevel          maximum purchasable level (≥ 1)
 * @param baseCost          crumbs cost of the first level
 * @param effect            effect type
 * @param valuePerLevel     effect magnitude per level (percent, cookies, or units depending on the type)
 * @param targetGeneratorId generator for {@link PrestigeTreeEffectType#STARTING_GENERATORS}, else {@code null}
 */
public record PrestigeTreeNode(
        String id,
        String nameKey,
        int maxLevel,
        long baseCost,
        PrestigeTreeEffectType effect,
        double valuePerLevel,
        String targetGeneratorId
) {

    public PrestigeTreeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(effect, "effect");
        if (maxLevel < 1) throw new IllegalArgumentException("maxLevel must be >= 1 for node " + id);
        if (baseCost < 1) throw new IllegalArgumentException("baseCost must be >= 1 for node " + id);
        if (effect == PrestigeTreeEffectType.STARTING_GENERATORS && (targetGeneratorId == null || targetGeneratorId.isBlank())) {
            throw new IllegalArgumentException("STARTING_GENERATORS node " + id + " requires a targetGeneratorId");
        }
    }

    public static PrestigeTreeNode of(String id, int maxLevel, long baseCost, PrestigeTreeEffectType effect, double valuePerLevel) {
        return new PrestigeTreeNode(id, "cookie.tree." + id, maxLevel, baseCost, effect, valuePerLevel, null);
    }

    /** Crumbs cost to go from {@code currentLevel} to {@code currentLevel + 1}. */
    public long costForLevel(int currentLevel) {
        if (currentLevel < 0) throw new IllegalArgumentException("level must be >= 0");
        if (currentLevel >= 62) return Long.MAX_VALUE;
        long shifted = baseCost << currentLevel;
        if (shifted < 0 || (shifted >> currentLevel) != baseCost) return Long.MAX_VALUE;
        return shifted;
    }

    /** Total effect magnitude at the given level. */
    public double totalValue(int level) {
        return valuePerLevel * Math.max(0, Math.min(level, maxLevel));
    }
}
