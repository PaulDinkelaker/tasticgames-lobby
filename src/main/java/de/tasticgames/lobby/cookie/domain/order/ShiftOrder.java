package de.tasticgames.lobby.cookie.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One running shift order: a goal, the progress towards it and the reward that is paid when it is done.
 * <p>
 * The reward is expressed in seconds of the player's own production ({@code rewardCpsSeconds}), so an order
 * is worth the same relative amount at 10 cookies per second and at 1e20 cookies per second – exactly like
 * the special cookies. The concrete amount is calculated when the order is claimed, never when it is rolled.
 */
public final class ShiftOrder {

    private final OrderType type;
    private final BigDecimal target;
    private final int rewardCpsSeconds;
    private BigDecimal progress = BigDecimal.ZERO;
    private boolean claimed;

    public ShiftOrder(OrderType type, BigDecimal target, int rewardCpsSeconds) {
        this.type = Objects.requireNonNull(type, "type");
        this.target = Objects.requireNonNull(target, "target");
        if (target.signum() <= 0) {
            throw new IllegalArgumentException("target must be positive");
        }
        this.rewardCpsSeconds = rewardCpsSeconds;
    }

    public OrderType type() {
        return type;
    }

    public BigDecimal target() {
        return target;
    }

    public BigDecimal progress() {
        return progress;
    }

    public int rewardCpsSeconds() {
        return rewardCpsSeconds;
    }

    public boolean claimed() {
        return claimed;
    }

    /** Adds progress; anything beyond the target is ignored (an order is never worth more than its goal). */
    public void advance(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || complete()) {
            return;
        }
        progress = progress.add(amount).min(target);
    }

    public boolean complete() {
        return progress.compareTo(target) >= 0;
    }

    /** Marks the reward as paid; a second claim returns {@code false}. */
    public boolean claim() {
        if (!complete() || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    /** Progress as a fraction between 0 and 1 (for the dialog bar). */
    public double fraction() {
        if (target.signum() <= 0) {
            return 1;
        }
        double value = progress.divide(target, 4, RoundingMode.DOWN).doubleValue();
        return Math.max(0, Math.min(1, value));
    }
}
