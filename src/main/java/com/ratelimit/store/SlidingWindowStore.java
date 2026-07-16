package com.ratelimit.store;

public interface SlidingWindowStore {

    record AddResult(boolean added, long count) {}

    /**
     * Atomically prunes entries older than windowStartNanos, then attempts to add
     * the timestamp. The add succeeds only if the current count is strictly less
     * than maxCount. Returns whether the add succeeded and the final count.
     */
    AddResult addAndCount(String key, long timestampNanos, long windowStartNanos, long maxCount);

    /**
     * Returns the count of entries within the window without adding.
     */
    long count(String key, long windowStartNanos);
}
