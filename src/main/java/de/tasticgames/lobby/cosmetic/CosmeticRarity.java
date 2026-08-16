package de.tasticgames.lobby.cosmetic;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;
import java.util.Optional;

public enum CosmeticRarity {
    COMMON(NamedTextColor.GRAY),
    UNCOMMON(NamedTextColor.GREEN),
    RARE(NamedTextColor.AQUA),
    EPIC(NamedTextColor.LIGHT_PURPLE),
    LEGENDARY(NamedTextColor.GOLD),
    MYTHIC(TextColor.color(0xFF4FA3));

    private final TextColor color;

    CosmeticRarity(TextColor color) {
        this.color = color;
    }

    public TextColor color() {
        return color;
    }

    public static Optional<CosmeticRarity> find(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
