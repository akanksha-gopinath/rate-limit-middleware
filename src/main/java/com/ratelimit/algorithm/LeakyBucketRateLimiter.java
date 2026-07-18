package com.ratelimit.algorithm;

import com.ratelimit.RateLimitConfig;
import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.store.BucketState;
import com.ratelimit.store.RateLimitStore;

import java.time.Clock;
import java.time.Duration;

public final class LeakyBucketRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final RateLimitStore store;
    private final Clock clock;

    public LeakyBucketRateLimiter(RateLimitConfig config, RateLimitStore store) {
        this(config, store, Clock.systemUTC());
    }

    public LeakyBucketRateLimiter(RateLimitConfig config, RateLimitStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public RateLimitResult peek(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        BucketState defaultState = BucketState.initial(0.0, nowNanos);

        BucketState current = store.computeIfAbsent(key, defaultState);
        long elapsedNanos = nowNanos - current.lastComputedNanos();
        double leaked = elapsedNanos * config.leakRatePerNano();
        double currentWater = Math.max(0.0, current.fillLevel() - leaked);

        long remaining = (long) (config.capacity() - currentWater);
        return RateLimitResult.allowed(remaining);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        BucketState defaultState = BucketState.initial(0.0, nowNanos);

        BucketState[] resultHolder = new BucketState[1];
        boolean[] accepted = new boolean[1];

        store.updateAtomically(key, defaultState, current -> {
            long elapsedNanos = nowNanos - current.lastComputedNanos();
            double leaked = elapsedNanos * config.leakRatePerNano();
            double currentWater = Math.max(0.0, current.fillLevel() - leaked);

            if (currentWater + 1.0 <= config.capacity()) {
                double newLevel = currentWater + 1.0;
                BucketState updated = new BucketState(newLevel, nowNanos);
                resultHolder[0] = updated;
                accepted[0] = true;
                return updated;
            } else {
                BucketState updated = new BucketState(currentWater, nowNanos);
                resultHolder[0] = updated;
                accepted[0] = false;
                return updated;
            }
        });

        if (accepted[0]) {
            long remaining = (long) (config.capacity() - resultHolder[0].fillLevel());
            return RateLimitResult.allowed(remaining);
        } else {
            double excessWater = resultHolder[0].fillLevel() + 1.0 - config.capacity();
            long nanosUntilSpace = (long) (excessWater / config.leakRatePerNano());
            return RateLimitResult.denied(Duration.ofNanos(nanosUntilSpace));
        }
    }
}
