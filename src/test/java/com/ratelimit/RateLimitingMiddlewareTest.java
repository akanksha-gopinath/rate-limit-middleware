package com.ratelimit;

import com.ratelimit.store.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitingMiddlewareTest {

    @Test
    void middlewareAllowsRequestWithinLimit() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.TOKEN_BUCKET)
                .config(RateLimitConfig.of(5, Duration.ofMinutes(1)))
                .keyResolver(req -> req)
                .handler(req -> "OK:" + req)
                .deniedHandler(result -> "DENIED")
                .build();

        String response = middleware.handle("user-1");
        assertEquals("OK:user-1", response);
    }

    @Test
    void middlewareDeniesWhenLimitExceeded() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.TOKEN_BUCKET)
                .config(RateLimitConfig.of(2, Duration.ofMinutes(1)))
                .keyResolver(req -> req)
                .handler(req -> "OK")
                .deniedHandler(result -> "DENIED:retryAfter=" + result.retryAfter().toMillis() + "ms")
                .build();

        middleware.handle("user-1");
        middleware.handle("user-1");
        String denied = middleware.handle("user-1");
        assertTrue(denied.startsWith("DENIED:retryAfter="));
    }

    @Test
    void middlewareIsolatesKeysByResolver() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.LEAKY_BUCKET)
                .config(RateLimitConfig.of(1, Duration.ofMinutes(1)))
                .keyResolver(req -> req)
                .handler(req -> "OK")
                .deniedHandler(result -> "DENIED")
                .build();

        assertEquals("OK", middleware.handle("user-a"));
        assertEquals("DENIED", middleware.handle("user-a"));
        assertEquals("OK", middleware.handle("user-b"));
    }

    @Test
    void directCheckWithoutHandler() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.TOKEN_BUCKET)
                .config(RateLimitConfig.of(3, Duration.ofSeconds(30)))
                .keyResolver(req -> req)
                .handler(req -> "OK")
                .deniedHandler(result -> "DENIED")
                .build();

        RateLimitResult r1 = middleware.check("api-key-xyz");
        assertTrue(r1.allowed());
        assertEquals(2, r1.remaining());
    }

    @Test
    void leakyBucketAlgorithmViaMiddleware() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.LEAKY_BUCKET)
                .config(RateLimitConfig.of(3, Duration.ofSeconds(10)))
                .keyResolver(req -> req)
                .handler(req -> "OK")
                .deniedHandler(result -> "DENIED")
                .build();

        assertEquals("OK", middleware.handle("client-1"));
        assertEquals("OK", middleware.handle("client-1"));
        assertEquals("OK", middleware.handle("client-1"));
        assertEquals("DENIED", middleware.handle("client-1"));
    }

    @Test
    void defaultsToInMemoryStore() {
        var middleware = RateLimitingMiddleware.<String, String>builder()
                .algorithm(Algorithm.TOKEN_BUCKET)
                .config(RateLimitConfig.of(10, Duration.ofMinutes(1)))
                .keyResolver(req -> req)
                .handler(req -> "OK")
                .deniedHandler(result -> "DENIED")
                .build();

        // Should work without explicitly setting a store
        assertEquals("OK", middleware.handle("test"));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfig.of(0, Duration.ofMinutes(1)));

        assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfig.of(10, Duration.ZERO));

        assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfig.of(-1, Duration.ofMinutes(1)));
    }
}
