package de.tasticgames.lobby.cookie.domain.model;

import java.util.Objects;

/**
 * Outcome of a generator or upgrade purchase.
 *
 * @param success      whether anything was bought
 * @param reason       failure reason ({@link Reason#OK} on success)
 * @param itemId       generator or upgrade id
 * @param count        units bought (0 on failure; 1 for upgrades)
 * @param totalCost    cookies spent
 * @param newOwned     owned count after the purchase (generators) or 1 for an owned upgrade
 * @param cookiesAfter bank after the purchase
 */
public record PurchaseResult(
        boolean success,
        Reason reason,
        String itemId,
        int count,
        CookieAmount totalCost,
        int newOwned,
        CookieAmount cookiesAfter
) {

    public enum Reason {
        OK,
        UNKNOWN_ITEM,
        LOCKED,
        INVALID_COUNT,
        INSUFFICIENT_FUNDS,
        ALREADY_OWNED,
        REQUIREMENT_NOT_MET
    }

    public PurchaseResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(totalCost, "totalCost");
        Objects.requireNonNull(cookiesAfter, "cookiesAfter");
    }

    public static PurchaseResult failure(Reason reason, String itemId, int owned, CookieAmount cookies) {
        return new PurchaseResult(false, reason, itemId, 0, CookieAmount.ZERO, owned, cookies);
    }

    public static PurchaseResult success(String itemId, int count, CookieAmount totalCost, int newOwned, CookieAmount cookies) {
        return new PurchaseResult(true, Reason.OK, itemId, count, totalCost, newOwned, cookies);
    }
}
