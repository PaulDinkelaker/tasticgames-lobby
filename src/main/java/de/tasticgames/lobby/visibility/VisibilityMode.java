package de.tasticgames.lobby.visibility;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

public enum VisibilityMode {
    ALL(Material.LIME_DYE),
    FRIENDS(Material.YELLOW_DYE),
    PARTY(Material.LIGHT_BLUE_DYE),
    FRIENDS_AND_PARTY(Material.ORANGE_DYE),
    NONE(Material.GRAY_DYE);

    private final Material material;

    VisibilityMode(Material material) {
        this.material = material;
    }

    public Material material() {
        return material;
    }

    public VisibilityMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Optional<VisibilityMode> find(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
