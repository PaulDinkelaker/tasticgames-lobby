package de.tasticgames.lobby.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    private static final List<Integer> LEVELS = IntStream.rangeClosed(1, 100).boxed().toList();

    @Test
    void hundredTiersAreTenPagesOfTen() {
        assertEquals(10, Pagination.pages(100, 10));
        Pagination.Page<Integer> first = Pagination.of(LEVELS, 0, 10);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), first.items());
        assertEquals(1, first.number());
        assertEquals(100, first.total());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());

        Pagination.Page<Integer> last = Pagination.of(LEVELS, 9, 10);
        assertEquals(List.of(91, 92, 93, 94, 95, 96, 97, 98, 99, 100), last.items());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void pagesAreClampedIntoRange() {
        assertEquals(9, Pagination.of(LEVELS, 42, 10).index(), "a stale button must not run out of range");
        assertEquals(0, Pagination.of(LEVELS, -5, 10).index());
        assertEquals(0, Pagination.clamp(7, 0, 10));
    }

    @Test
    void partialAndEmptyLists() {
        Pagination.Page<Integer> partial = Pagination.of(List.of(1, 2, 3), 0, 10);
        assertEquals(1, partial.pages());
        assertEquals(3, partial.items().size());

        Pagination.Page<Integer> empty = Pagination.of(List.of(), 3, 10);
        assertEquals(1, empty.pages());
        assertEquals(0, empty.total());
        assertTrue(empty.items().isEmpty());
        assertFalse(empty.hasNext());
    }

    @Test
    void pageSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Pagination.of(LEVELS, 0, 0));
    }
}
