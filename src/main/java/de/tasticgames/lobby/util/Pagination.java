package de.tasticgames.lobby.util;

import java.util.List;
import java.util.Objects;

/**
 * Page slicing for dialog lists that do not fit on one screen (the pass reward track has 100 tiers).
 * Page indices are zero based and always clamped into range, so a stale button from a previously
 * shown dialog can never produce an out-of-bounds page.
 */
public final class Pagination {

    private Pagination() {
    }

    /**
     * @param index zero based page index
     * @param pages total number of pages (at least 1, even for an empty list)
     * @param total number of items across all pages
     * @param items items of this page (empty when the list is empty)
     */
    public record Page<T>(int index, int pages, int total, List<T> items) {

        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        public boolean hasPrevious() {
            return index > 0;
        }

        public boolean hasNext() {
            return index < pages - 1;
        }

        /** Page number for display (1 based). */
        public int number() {
            return index + 1;
        }
    }

    public static int pages(int total, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
        return Math.max(1, (Math.max(0, total) + pageSize - 1) / pageSize);
    }

    /** Clamps {@code page} into {@code 0..pages(total, pageSize) - 1}. */
    public static int clamp(int page, int total, int pageSize) {
        return Math.clamp(page, 0, pages(total, pageSize) - 1);
    }

    public static <T> Page<T> of(List<T> items, int page, int pageSize) {
        Objects.requireNonNull(items, "items");
        int pages = pages(items.size(), pageSize);
        int index = clamp(page, items.size(), pageSize);
        int from = Math.min(index * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return new Page<>(index, pages, items.size(), items.subList(from, to));
    }
}
