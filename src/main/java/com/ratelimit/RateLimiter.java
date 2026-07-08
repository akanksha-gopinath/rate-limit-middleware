package com.ratelimit;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key);
}
