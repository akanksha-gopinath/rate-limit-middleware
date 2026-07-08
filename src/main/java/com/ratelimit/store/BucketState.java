package com.ratelimit.store;

public record BucketState(double level, long lastUpdateNanos) {

    public static BucketState initial(double level, long nowNanos) {
        return new BucketState(level, nowNanos);
    }
}
