package de.tasticgames.lobby.cookie.domain.model;

/**
 * Outcome of a prestige tree node purchase.
 *
 * @param success     whether a level was bought
 * @param reason      failure reason
 * @param nodeId      node id
 * @param newLevel    node level after the purchase
 * @param crumbsSpent crumbs spent
 * @param crumbsAfter crumbs remaining
 */
public record NodePurchaseResult(boolean success, Reason reason, String nodeId, int newLevel, long crumbsSpent, long crumbsAfter) {

    public enum Reason {
        OK,
        UNKNOWN_NODE,
        MAX_LEVEL,
        INSUFFICIENT_CRUMBS
    }

    public static NodePurchaseResult failure(Reason reason, String nodeId, int level, long crumbs) {
        return new NodePurchaseResult(false, reason, nodeId, level, 0, crumbs);
    }
}
