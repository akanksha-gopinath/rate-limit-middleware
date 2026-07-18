package com.ratelimit.store;

public record BucketState(double fillLevel, long lastComputedNanos) {

    public static BucketState initial(double fillLevel, long nowNanos) {
        return new BucketState(fillLevel, nowNanos);
    }
}
