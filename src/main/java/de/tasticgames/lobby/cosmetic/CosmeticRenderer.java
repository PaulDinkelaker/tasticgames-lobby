package de.tasticgames.lobby.cosmetic;

import org.bukkit.entity.Player;

/**
 * Renders equipped cosmetics for a player. Implementations must be idempotent and clean up on unequip/quit.
 */
public interface CosmeticRenderer {

    String id();

    boolean supports(CosmeticCategory category);

    /** Applies the equipped cosmetic (may be null to clear the category). */
    void apply(Player player, CosmeticCategory category, CosmeticDefinition definition, boolean reducedEffects);

    /** Periodic tick for particle-based cosmetics (called every 10 ticks on the main thread). */
    default void tick(Player player, CosmeticDefinition definition, boolean reducedEffects) {
    }

    void clear(Player player);
}
