package com.ratelimit.algorithm;

import com.ratelimit.RateLimitConfig;
import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.store.SlidingWindowStore;

import java.time.Clock;
import java.time.Duration;

public final class SlidingWindowLogRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final SlidingWindowStore store;
    private final Clock clock;
    private final long windowSizeNanos;

    public SlidingWindowLogRateLimiter(RateLimitConfig config, SlidingWindowStore store) {
        this(config, store, Clock.systemUTC());
    }

    public SlidingWindowLogRateLimiter(RateLimitConfig config, SlidingWindowStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.windowSizeNanos = config.refillPeriod().toNanos();
    }

    @Override
    public RateLimitResult peek(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long windowStartNanos = nowNanos - windowSizeNanos;
        long count = store.count(key, windowStartNanos);
        long remaining = Math.max(0, config.capacity() - count);
        return RateLimitResult.allowed(remaining);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long windowStartNanos = nowNanos - windowSizeNanos;

        SlidingWindowStore.AddResult result = store.addAndCount(key, nowNanos, windowStartNanos, config.capacity());

        if (result.added()) {
            long remaining = config.capacity() - result.count();
            return RateLimitResult.allowed(remaining);
        } else {
            long nanosPerSlot = windowSizeNanos / config.capacity();
            return RateLimitResult.denied(Duration.ofNanos(nanosPerSlot));
        }
    }
}
