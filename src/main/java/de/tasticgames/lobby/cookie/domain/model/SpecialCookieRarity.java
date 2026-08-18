package de.tasticgames.lobby.cookie.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rarity of a special cookie. Special cookies are a prestige-1 unlock – a profile at prestige 0 draws
 * none at all – and every rarity adds itself to the draw at its own prestige level. The draw uses the
 * {@link #baseWeight()} of the unlocked rarities, normalised over that set; the balancing may replace
 * the weights and the {@code SPECIAL_RARITY_WEIGHT} upgrades / the {@code connoisseur} tree node scale
 * them per player.
 */
public enum SpecialCookieRarity {

    /** Prestige 1, the common one. */
    SILVER(1, 60.0, "#c8d2dc", 1, "Silver"),
    /** Prestige 1, the classic golden cookie. */
    GOLDEN(1, 30.0, "#ffd82b", 2, "Golden"),
    /** Prestige 2. */
    PLATINUM(2, 7.0, "#8ef6ff", 3, "Platinum"),
    /** Prestige 4, first rarity with the {@code BLESSING} reward. */
    DIAMOND(4, 3.0, "#5bf0c8", 4, "Diamond"),
    /** Prestige 7, the jackpot. */
    MASTER(7, 1.5, "#ff5ecb", 5, "Master");

    /** Lowest rarity the {@code connoisseur} tree node improves ("platinum and above"). */
    public static final SpecialCookieRarity RARE_FROM = PLATINUM;

    private final int unlockPrestige;
    private final double baseWeight;
    private final String color;
    private final int passXpMultiplier;
    private final String displayName;

    SpecialCookieRarity(int unlockPrestige, double baseWeight, String color, int passXpMultiplier, String displayName) {
        this.unlockPrestige = unlockPrestige;
        this.baseWeight = baseWeight;
        this.color = color;
        this.passXpMultiplier = passXpMultiplier;
        this.displayName = displayName;
    }

    /** Prestige level at which the rarity joins the draw. */
    public int unlockPrestige() {
        return unlockPrestige;
    }

    /** Catalog draw weight before balancing overrides and per-player multipliers. */
    public double baseWeight() {
        return baseWeight;
    }

    /** Display colour as a hex string, e.g. {@code #ffd82b}. */
    public String color() {
        return color;
    }

    /** Factor applied to the configured pass XP rate of a special cookie (SILVER 1x … MASTER 5x). */
    public int passXpMultiplier() {
        return passXpMultiplier;
    }

    /** Fallback display name used where no message bundle is available (admin output, stats lines). */
    public String displayName() {
        return displayName;
    }

    /** Stable lowercase id used in configuration keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Translation key of the localized, coloured display name. */
    public String nameKey() {
        return "cookie.special." + id() + ".name";
    }

    public boolean isUnlockedAt(int prestigeLevel) {
        return prestigeLevel >= unlockPrestige;
    }

    /** Whether this rarity is at least as rare as {@code other} (declaration order = ascending rarity). */
    public boolean atLeast(SpecialCookieRarity other) {
        return ordinal() >= other.ordinal();
    }

    /** All rarities available at the given prestige level, in declaration order (empty at prestige 0). */
    public static List<SpecialCookieRarity> unlockedFor(int prestigeLevel) {
        List<SpecialCookieRarity> unlocked = new ArrayList<>(values().length);
        for (SpecialCookieRarity rarity : values()) {
            if (rarity.isUnlockedAt(prestigeLevel)) unlocked.add(rarity);
        }
        return List.copyOf(unlocked);
    }

    /** Rarities a prestige from {@code fromLevel} to {@code toLevel} newly unlocks. */
    public static List<SpecialCookieRarity> unlockedBetween(int fromLevel, int toLevel) {
        List<SpecialCookieRarity> gained = new ArrayList<>(values().length);
        for (SpecialCookieRarity rarity : values()) {
            if (!rarity.isUnlockedAt(fromLevel) && rarity.isUnlockedAt(toLevel)) gained.add(rarity);
        }
        return List.copyOf(gained);
    }
}
