package de.tasticgames.lobby.cookie.domain.model;

import java.util.Arrays;

/**
 * Bounded ring buffer of click timestamps (epoch millis) used for server-side rate limiting.
 * Not thread-safe; the owning profile is used single-threaded.
 */
public final class ClickHistory {

    public static final int DEFAULT_CAPACITY = 256;

    private final long[] stamps;
    private int head;   // index of next write
    private int size;

    public ClickHistory() {
        this(DEFAULT_CAPACITY);
    }

    public ClickHistory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.stamps = new long[capacity];
        Arrays.fill(stamps, Long.MIN_VALUE);
    }

    public int capacity() {
        return stamps.length;
    }

    public int size() {
        return size;
    }

    /** Records a click at {@code epochMillis}. */
    public void record(long epochMillis) {
        stamps[head] = epochMillis;
        head = (head + 1) % stamps.length;
        if (size < stamps.length) size++;
    }

    /** Number of recorded clicks with timestamp {@code > sinceExclusiveMillis} (bounded by capacity). */
    public int countAfter(long sinceExclusiveMillis) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (stamps[i] > sinceExclusiveMillis) count++;
        }
        return count;
    }

    /** Timestamp of the most recent click, or {@link Long#MIN_VALUE} if none. */
    public long last() {
        if (size == 0) return Long.MIN_VALUE;
        int idx = (head - 1 + stamps.length) % stamps.length;
        return stamps[idx];
    }

    public void clear() {
        Arrays.fill(stamps, Long.MIN_VALUE);
        head = 0;
        size = 0;
    }
}
