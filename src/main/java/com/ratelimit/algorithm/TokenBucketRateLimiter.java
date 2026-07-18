package com.ratelimit.algorithm;

import com.ratelimit.RateLimitConfig;
import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.store.BucketState;
import com.ratelimit.store.RateLimitStore;

import java.time.Clock;
import java.time.Duration;

public final class TokenBucketRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final RateLimitStore store;
    private final Clock clock;

    public TokenBucketRateLimiter(RateLimitConfig config, RateLimitStore store) {
        this(config, store, Clock.systemUTC());
    }

    public TokenBucketRateLimiter(RateLimitConfig config, RateLimitStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public RateLimitResult peek(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        BucketState defaultState = BucketState.initial(config.capacity(), nowNanos);

        BucketState current = store.computeIfAbsent(key, defaultState);
        long elapsedNanos = nowNanos - current.lastComputedNanos();
        double tokensToAdd = elapsedNanos * config.refillRatePerNano();
        double availableTokens = Math.min(config.capacity(), current.fillLevel() + tokensToAdd);

        return RateLimitResult.allowed((long) availableTokens);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        BucketState defaultState = BucketState.initial(config.capacity(), nowNanos);

        BucketState[] resultHolder = new BucketState[1];
        boolean[] consumed = new boolean[1];

        store.updateAtomically(key, defaultState, current -> {
            long elapsedNanos = nowNanos - current.lastComputedNanos();
            double tokensToAdd = elapsedNanos * config.refillRatePerNano();
            double availableTokens = Math.min(config.capacity(), current.fillLevel() + tokensToAdd);

            if (availableTokens >= 1.0) {
                double newLevel = availableTokens - 1.0;
                BucketState updated = new BucketState(newLevel, nowNanos);
                resultHolder[0] = updated;
                consumed[0] = true;
                return updated;
            } else {
                BucketState updated = new BucketState(availableTokens, nowNanos);
                resultHolder[0] = updated;
                consumed[0] = false;
                return updated;
            }
        });

        if (consumed[0]) {
            return RateLimitResult.allowed((long) resultHolder[0].fillLevel());
        } else {
            double tokensNeeded = 1.0 - resultHolder[0].fillLevel();
            long nanosUntilToken = (long) (tokensNeeded / config.refillRatePerNano());
            return RateLimitResult.denied(Duration.ofNanos(nanosUntilToken));
        }
    }
}
