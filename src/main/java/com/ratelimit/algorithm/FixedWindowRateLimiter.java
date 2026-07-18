package com.ratelimit.algorithm;

import com.ratelimit.RateLimitConfig;
import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.store.FixedWindowState;
import com.ratelimit.store.FixedWindowStore;

import java.time.Clock;
import java.time.Duration;

public final class FixedWindowRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final FixedWindowStore store;
    private final Clock clock;
    private final long windowSizeNanos;

    public FixedWindowRateLimiter(RateLimitConfig config, FixedWindowStore store) {
        this(config, store, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(RateLimitConfig config, FixedWindowStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.windowSizeNanos = config.refillPeriod().toNanos();
    }

    @Override
    public RateLimitResult peek(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long currentWindowStart = (nowNanos / windowSizeNanos) * windowSizeNanos;
        FixedWindowState defaultState = FixedWindowState.initial(currentWindowStart);

        FixedWindowState current = store.computeIfAbsent(key, defaultState);
        if (current.windowStartNanos() != currentWindowStart) {
            return RateLimitResult.allowed(config.capacity());
        }
        long remaining = config.capacity() - current.requestCount();
        return RateLimitResult.allowed(remaining);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        long currentWindowStart = (nowNanos / windowSizeNanos) * windowSizeNanos;
        FixedWindowState defaultState = FixedWindowState.initial(currentWindowStart);

        FixedWindowState[] resultHolder = new FixedWindowState[1];
        boolean[] allowed = new boolean[1];

        store.updateAtomically(key, defaultState, current -> {
            if (current.windowStartNanos() != currentWindowStart) {
                FixedWindowState fresh = new FixedWindowState(1, currentWindowStart);
                resultHolder[0] = fresh;
                allowed[0] = true;
                return fresh;
            }

            if (current.requestCount() < config.capacity()) {
                FixedWindowState updated = new FixedWindowState(current.requestCount() + 1, currentWindowStart);
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
            long remaining = config.capacity() - resultHolder[0].requestCount();
            return RateLimitResult.allowed(remaining);
        } else {
            long windowEndNanos = currentWindowStart + windowSizeNanos;
            long nanosUntilReset = windowEndNanos - nowNanos;
            return RateLimitResult.denied(Duration.ofNanos(nanosUntilReset));
        }
    }
}
