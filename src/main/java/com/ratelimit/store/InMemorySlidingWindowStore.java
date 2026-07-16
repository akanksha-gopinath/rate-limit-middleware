package com.ratelimit.store;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySlidingWindowStore implements SlidingWindowStore {

    private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();

    @Override
    public SlidingWindowStore.AddResult addAndCount(String key, long timestampNanos, long windowStartNanos, long maxCount) {
        Deque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() < windowStartNanos) {
                log.pollFirst();
            }
            if (log.size() >= maxCount) {
                return new AddResult(false, log.size());
            }
            log.addLast(timestampNanos);
            return new AddResult(true, log.size());
        }
    }

    @Override
    public long count(String key, long windowStartNanos) {
        Deque<Long> log = logs.get(key);
        if (log == null) return 0;
        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() < windowStartNanos) {
                log.pollFirst();
            }
            return log.size();
        }
    }

    public void clear() {
        logs.clear();
    }
}
