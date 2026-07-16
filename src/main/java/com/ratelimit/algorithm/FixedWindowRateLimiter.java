package com.ratelimit.algorithm;

import com.ratelimit.RateLimitConfig;
import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.store.BucketState;
import com.ratelimit.store.RateLimitStore;

import java.time.Clock;
import java.time.Duration;

public final class FixedWindowRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final RateLimitStore store;
    private final Clock clock;
    private final long windowSizeNanos;

    public FixedWindowRateLimiter(RateLimitConfig config, RateLimitStore store) {
        this(config, store, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(RateLimitConfig config, RateLimitStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.windowSizeNanos = config.refillPeriod().toNanos();
    }

    @Override
    public RateLimitResult peek(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long currentWindowStart = (nowNanos / windowSizeNanos) * windowSizeNanos;
        BucketState defaultState = new BucketState(0.0, currentWindowStart);

        BucketState current = store.computeIfAbsent(key, defaultState);
        if (current.lastUpdateNanos() != currentWindowStart) {
            return RateLimitResult.allowed(config.capacity());
        }
        long remaining = config.capacity() - (long) current.level();
        return RateLimitResult.allowed(remaining);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long currentWindowStart = (nowNanos / windowSizeNanos) * windowSizeNanos;
        BucketState defaultState = new BucketState(0.0, currentWindowStart);

        BucketState[] resultHolder = new BucketState[1];
        boolean[] allowed = new boolean[1];

        store.updateAtomically(key, defaultState, current -> {
            if (current.lastUpdateNanos() != currentWindowStart) {
                // Window has rolled over — reset count
                BucketState fresh = new BucketState(1.0, currentWindowStart);
                resultHolder[0] = fresh;
                allowed[0] = true;
                return fresh;
            }

            if (current.level() < config.capacity()) {
                BucketState updated = new BucketState(current.level() + 1.0, currentWindowStart);
                resultHolder[0] = updated;
                allowed[0] = true;
                return updated;
            } else {
                resultHolder[0] = current;
                allowed[0] = false;
                return current;
            }
        });

        if (allowed[0]) {
            long remaining = config.capacity() - (long) resultHolder[0].level();
            return RateLimitResult.allowed(remaining);
        } else {
            long windowEndNanos = currentWindowStart + windowSizeNanos;
            long nanosUntilReset = windowEndNanos - nowNanos;
            return RateLimitResult.denied(Duration.ofNanos(nanosUntilReset));
        }
    }
}
