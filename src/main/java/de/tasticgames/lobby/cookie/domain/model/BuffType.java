package de.tasticgames.lobby.cookie.domain.model;

/** Kinds of temporary buffs. */
public enum BuffType {
    /** Multiplies passive CPS only. */
    CPS_MULTIPLIER(true, false),
    /** Multiplies click power only. */
    CLICK_MULTIPLIER(false, true),
    /** Multiplies both CPS and click power. */
    FRENZY(true, true),
    /** Multiplies click power only (golden Click Frenzy). */
    CLICK_FRENZY(false, true);

    private final boolean affectsCps;
    private final boolean affectsClicks;

    BuffType(boolean affectsCps, boolean affectsClicks) {
        this.affectsCps = affectsCps;
        this.affectsClicks = affectsClicks;
    }

    public boolean affectsCps() {
        return affectsCps;
    }

    public boolean affectsClicks() {
        return affectsClicks;
    }
}
