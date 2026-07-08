package com.ratelimit;

import com.ratelimit.algorithm.LeakyBucketRateLimiter;
import com.ratelimit.algorithm.TokenBucketRateLimiter;
import com.ratelimit.store.InMemoryStore;

import java.time.Duration;

public final class Demo {

    public static void main(String[] args) {
        System.out.println("=== Token Bucket Demo ===");
        tokenBucketDemo();

        System.out.println();
        System.out.println("=== Leaky Bucket Demo ===");
        leakyBucketDemo();

        System.out.println();
        System.out.println("=== Middleware Demo ===");
        middlewareDemo();
    }

    private static void tokenBucketDemo() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var store = new InMemoryStore();
        var limiter = new TokenBucketRateLimiter(config, store);

        String clientKey = "user-123";

        for (int i = 1; i <= 8; i++) {
            RateLimitResult result = limiter.tryAcquire(clientKey);
            System.out.printf("  Request %d: %s (remaining: %d, retryAfter: %dms)%n",
                    i,
                    result.allowed() ? "ALLOWED" : "DENIED",
                    result.remaining(),
                    result.retryAfter().toMillis());
        }
    }

    private static void leakyBucketDemo() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var store = new InMemoryStore();
        var limiter = new LeakyBucketRateLimiter(config, store);

        String clientKey = "user-456";

        for (int i = 1; i <= 8; i++) {
            RateLimitResult result = limiter.tryAcquire(clientKey);
            System.out.printf("  Request %d: %s (remaining: %d, retryAfter: %dms)%n",
                    i,
                    result.allowed() ? "ALLOWED" : "DENIED",
                    result.remaining(),
                    result.retryAfter().toMillis());
        }
    }

    private static void middlewareDemo() {
        RateLimitingMiddleware<String, String> middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.TOKEN_BUCKET)
                .config(RateLimitConfig.of(3, Duration.ofMinutes(1)))
                .keyResolver(request -> request)
                .handler(request -> "200 OK: Hello, " + request)
                .deniedHandler(result -> "429 Too Many Requests (retry after " + result.retryAfter().toMillis() + "ms)")
                .build();

        for (int i = 1; i <= 5; i++) {
            String response = middleware.handle("api-client-1");
            System.out.printf("  Request %d: %s%n", i, response);
        }
    }
}
