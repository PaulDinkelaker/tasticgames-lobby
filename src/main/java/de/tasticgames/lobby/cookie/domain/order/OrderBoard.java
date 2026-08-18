package de.tasticgames.lobby.cookie.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The three shift orders a player is working on. The board rolls a replacement as soon as an order is
 * claimed, so there is always something to work towards, and it caps how many orders one player can finish
 * per hour – the reward scales with the production, so without the cap a fast clicker could turn the board
 * into an income source of its own.
 * <p>
 * Targets are rolled from the player's current production: an order asks for roughly {@code targetSeconds}
 * of what the player produces anyway, which keeps them meaningful from the first run up to prestige 10.
 */
public final class OrderBoard {

    /** How many orders run at the same time. */
    public static final int SLOTS = 3;
    /** Orders one player can claim per hour. */
    public static final int CLAIMS_PER_HOUR = 8;

    private final List<ShiftOrder> orders = new ArrayList<>();
    private final List<Long> claimTimes = new ArrayList<>();

    /**
     * Rolls all missing slots so the board is full again; existing orders keep their progress. Every slot gets
     * a different goal type, so the board always offers three different things to do.
     */
    public void fill(BigDecimal cps, BigDecimal clickValue, Random random) {
        while (orders.size() < SLOTS) {
            orders.add(roll(cps, clickValue, random, free()));
        }
    }

    /** The order in {@code slot}, or {@code null} when the board has not been filled yet. */
    public ShiftOrder order(int slot) {
        return slot >= 0 && slot < orders.size() ? orders.get(slot) : null;
    }

    public List<ShiftOrder> orders() {
        return List.copyOf(orders);
    }

    /**
     * Claims a finished order and replaces it. Returns the claimed order, or {@code null} when the slot is
     * empty, the order is not finished yet or the hourly limit is reached.
     */
    public ShiftOrder claim(int slot, BigDecimal cps, BigDecimal clickValue, Random random, long nowMillis) {
        ShiftOrder order = order(slot);
        if (order == null || !order.complete() || order.claimed() || limitReached(nowMillis)) {
            return null;
        }
        if (!order.claim()) {
            return null;
        }
        claimTimes.add(nowMillis);
        orders.remove(slot);
        orders.add(slot, roll(cps, clickValue, random, free()));
        return order;
    }

    /** Whether the player already claimed {@link #CLAIMS_PER_HOUR} orders within the last hour. */
    public boolean limitReached(long nowMillis) {
        claimTimes.removeIf(time -> nowMillis - time > 3_600_000L);
        return claimTimes.size() >= CLAIMS_PER_HOUR;
    }

    /** Adds progress to every unfinished order of that type. */
    public void advance(OrderType type, BigDecimal amount) {
        for (ShiftOrder order : orders) {
            if (order.type() == type) {
                order.advance(amount);
            }
        }
    }

    /** Number of orders that are finished and waiting to be claimed. */
    public int claimable() {
        return (int) orders.stream().filter(o -> o.complete() && !o.claimed()).count();
    }

    /** Goal types that are not on the board right now (falls back to all of them if the board ever grows). */
    private EnumSet<OrderType> free() {
        EnumSet<OrderType> pool = EnumSet.allOf(OrderType.class);
        orders.forEach(order -> pool.remove(order.type()));
        return pool.isEmpty() ? EnumSet.allOf(OrderType.class) : pool;
    }

    private static ShiftOrder roll(BigDecimal cps, BigDecimal clickValue, Random random, EnumSet<OrderType> pool) {
        Objects.requireNonNull(random, "random");
        List<OrderType> types = new ArrayList<>(pool);
        OrderType type = types.get(random.nextInt(types.size()));
        return switch (type) {
            // clicking does not scale with the production, so this one stays a flat, honest amount of clicks
            case CLICKS -> new ShiftOrder(type, BigDecimal.valueOf(100L + random.nextInt(4) * 50L), 150);
            case COOKIES -> new ShiftOrder(type, cookieTarget(cps, clickValue, 180 + random.nextInt(4) * 60), 240);
            case GENERATORS -> new ShiftOrder(type, BigDecimal.valueOf(10L + random.nextInt(3) * 5L), 180);
            case UPGRADES -> new ShiftOrder(type, BigDecimal.valueOf(2L + random.nextInt(2)), 210);
            case SPECIALS -> new ShiftOrder(type, BigDecimal.ONE, 300);
        };
    }

    /**
     * Cookies to bake: {@code seconds} of production, but at least what 60 clicks are worth, so the order is
     * reachable in the very first minutes when there is no production at all yet.
     */
    private static BigDecimal cookieTarget(BigDecimal cps, BigDecimal clickValue, int seconds) {
        BigDecimal fromProduction = cps.multiply(BigDecimal.valueOf(seconds));
        BigDecimal fromClicks = clickValue.multiply(BigDecimal.valueOf(60));
        BigDecimal target = fromProduction.max(fromClicks).setScale(0, RoundingMode.UP);
        return target.signum() <= 0 ? BigDecimal.TEN : target;
    }
}
