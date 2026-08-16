package de.tasticgames.lobby.cosmetic;

import java.util.Locale;
import java.util.Optional;

public enum CosmeticCategory {
    PROFILE_FRAME, PROFILE_BACKGROUND, TITLE, HAT, BACK_ITEM, AURA, TRAIL, PET, JOIN_EFFECT, LOBBY_GADGET;

    public static Optional<CosmeticCategory> find(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
