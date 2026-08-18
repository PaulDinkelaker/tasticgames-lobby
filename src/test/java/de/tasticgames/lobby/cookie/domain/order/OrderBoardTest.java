package de.tasticgames.lobby.cookie.domain.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBoardTest {

    private static final BigDecimal CPS = new BigDecimal("100");
    private static final BigDecimal CLICK = new BigDecimal("5");

    private OrderBoard filled(long seed) {
        OrderBoard board = new OrderBoard();
        board.fill(CPS, CLICK, new Random(seed));
        return board;
    }

    @Test
    void boardIsFilledWithThreeOrders() {
        OrderBoard board = filled(1);
        assertEquals(OrderBoard.SLOTS, board.orders().size());
        assertEquals(0, board.claimable());
        board.orders().forEach(order -> {
            assertTrue(order.target().signum() > 0, "target must be positive");
            assertTrue(order.rewardCpsSeconds() > 0);
            assertFalse(order.complete());
        });
    }

    @Test
    void fillKeepsExistingProgress() {
        OrderBoard board = filled(2);
        ShiftOrder first = board.order(0);
        first.advance(BigDecimal.ONE);
        board.fill(CPS, CLICK, new Random(3));
        assertEquals(first, board.order(0));
        assertEquals(BigDecimal.ONE, board.order(0).progress());
    }

    @Test
    void progressStopsAtTheTargetAndOnlyCountsItsOwnType() {
        OrderBoard board = filled(4);
        ShiftOrder order = board.order(0);
        board.advance(order.type(), order.target().multiply(BigDecimal.TEN));
        assertEquals(order.target(), order.progress());
        assertTrue(order.complete());
        assertEquals(1.0, order.fraction());
        assertEquals(1, board.claimable());
    }

    @Test
    void claimingPaysOnceAndRollsAReplacement() {
        OrderBoard board = filled(5);
        ShiftOrder order = board.order(0);
        board.advance(order.type(), order.target());
        ShiftOrder claimed = board.claim(0, CPS, CLICK, new Random(6), 1_000L);
        assertEquals(order, claimed);
        assertTrue(claimed.claimed());
        assertNotNull(board.order(0));
        assertFalse(board.order(0).complete(), "a fresh order takes the slot");
        assertEquals(0, board.claimable());
        assertNull(board.claim(0, CPS, CLICK, new Random(7), 1_100L), "the new order is not finished");
    }

    @Test
    void unfinishedOrdersCannotBeClaimed() {
        OrderBoard board = filled(8);
        assertNull(board.claim(0, CPS, CLICK, new Random(9), 1_000L));
        assertNull(board.claim(99, CPS, CLICK, new Random(9), 1_000L), "unknown slot");
    }

    @Test
    void hourlyLimitStopsFarmingAndFreesUpAfterAnHour() {
        OrderBoard board = filled(10);
        long now = 10_000L;
        for (int i = 0; i < OrderBoard.CLAIMS_PER_HOUR; i++) {
            ShiftOrder order = board.order(0);
            board.advance(order.type(), order.target());
            assertNotNull(board.claim(0, CPS, CLICK, new Random(11 + i), now + i), "claim " + i);
        }
        ShiftOrder blocked = board.order(0);
        board.advance(blocked.type(), blocked.target());
        assertTrue(board.limitReached(now + 100));
        assertNull(board.claim(0, CPS, CLICK, new Random(99), now + 100));

        long later = now + 3_600_001L;
        assertFalse(board.limitReached(later));
        assertNotNull(board.claim(0, CPS, CLICK, new Random(100), later));
    }

    @Test
    void cookieTargetsScaleWithTheProductionAndNeverCollapseToZero() {
        OrderBoard rich = new OrderBoard();
        rich.fill(new BigDecimal("1e12"), new BigDecimal("1e9"), new Random(12));
        OrderBoard poor = new OrderBoard();
        poor.fill(BigDecimal.ZERO, BigDecimal.ZERO, new Random(12));
        for (int slot = 0; slot < OrderBoard.SLOTS; slot++) {
            ShiftOrder a = rich.order(slot);
            ShiftOrder b = poor.order(slot);
            assertEquals(a.type(), b.type(), "same seed rolls the same types");
            assertTrue(b.target().signum() > 0, "a fresh profile still gets a reachable target");
            if (a.type() == OrderType.COOKIES) {
                assertTrue(a.target().compareTo(b.target()) > 0, "a big bakery gets a bigger order");
            }
        }
    }
}
