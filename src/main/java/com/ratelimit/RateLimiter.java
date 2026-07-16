package com.ratelimit;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key);

    default RateLimitResult peek(String key) {
        return tryAcquire(key);
    }
}
