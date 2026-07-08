package com.ratelimit;

import java.time.Duration;

public record RateLimitResult(boolean allowed, long remaining, Duration retryAfter) {

    public static RateLimitResult allowed(long remaining) {
        return new RateLimitResult(true, remaining, Duration.ZERO);
    }

    public static RateLimitResult denied(Duration retryAfter) {
        return new RateLimitResult(false, 0, retryAfter);
    }
}
