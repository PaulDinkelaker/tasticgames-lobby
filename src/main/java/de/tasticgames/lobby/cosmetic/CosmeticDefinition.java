package de.tasticgames.lobby.cosmetic;

import org.bukkit.Material;

import java.util.Objects;

/**
 * Catalog entry. Domain id (e.g. {@code prestige_5_royal_frame}) is independent from the render asset.
 *
 * @param unlockSource  DEFAULT (owned by everyone), PRESTIGE_<n>, ACHIEVEMENT_<id>, ADMIN
 * @param renderData    renderer specific data (particle name, material, colour, model key)
 */
public record CosmeticDefinition(
        String id,
        CosmeticCategory category,
        CosmeticRarity rarity,
        String nameKey,
        String descriptionKey,
        Material previewMaterial,
        String unlockSource,
        String unlockRequirementKey,
        String renderData,
        boolean enabled,
        int sortPriority
) {
    public CosmeticDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(category);
        Objects.requireNonNull(rarity);
        Objects.requireNonNull(nameKey);
        Objects.requireNonNull(previewMaterial);
        unlockSource = unlockSource == null ? "ADMIN" : unlockSource;
        descriptionKey = descriptionKey == null ? nameKey + ".description" : descriptionKey;
        unlockRequirementKey = unlockRequirementKey == null ? "" : unlockRequirementKey;
        renderData = renderData == null ? "" : renderData;
        if (!id.matches("[a-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid cosmetic id: " + id);
        }
    }

    public boolean defaultOwned() {
        return "DEFAULT".equalsIgnoreCase(unlockSource);
    }

    public int prestigeRequirement() {
        if (unlockSource.startsWith("PRESTIGE_")) {
            try {
                return Integer.parseInt(unlockSource.substring("PRESTIGE_".length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
