package com.ratelimit;

import java.time.Duration;

public record RateLimitConfig(long capacity, Duration refillPeriod) {

    public static RateLimitConfig of(long capacity, Duration refillPeriod) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (refillPeriod.isNegative() || refillPeriod.isZero())
            throw new IllegalArgumentException("refillPeriod must be positive");
        return new RateLimitConfig(capacity, refillPeriod);
    }

    public double refillRatePerNano() {
        return (double) capacity / refillPeriod.toNanos();
    }

    public double leakRatePerNano() {
        return (double) capacity / refillPeriod.toNanos();
    }
}
