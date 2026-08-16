package de.tasticgames.lobby.cookie.domain.model;

/** Bulk purchase modes. */
public enum BuyMode {
    ONE(1),
    TEN(10),
    HUNDRED(100),
    /** As many as affordable (at least one). */
    MAX(-1);

    private final int fixedCount;

    BuyMode(int fixedCount) {
        this.fixedCount = fixedCount;
    }

    /** Fixed count, or -1 for {@link #MAX}. */
    public int fixedCount() {
        return fixedCount;
    }

    public boolean isMax() {
        return this == MAX;
    }
}
