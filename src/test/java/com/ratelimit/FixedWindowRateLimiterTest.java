package com.ratelimit;

import com.ratelimit.algorithm.FixedWindowRateLimiter;
import com.ratelimit.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowRateLimiterTest {

    private InMemoryStore store;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void allowsRequestsUpToCapacity() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire("client-1");
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void deniesAfterCapacityExhausted() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }

        RateLimitResult denied = limiter.tryAcquire("client-1");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remaining());
        assertTrue(denied.retryAfter().toNanos() > 0);
    }

    @Test
    void resetsAtWindowBoundary() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }
        assertFalse(limiter.tryAcquire("client-1").allowed());

        // Advance past the window boundary
        clock.advance(Duration.ofSeconds(10));

        RateLimitResult allowed = limiter.tryAcquire("client-1");
        assertTrue(allowed.allowed());
    }

    @Test
    void doesNotResetMidWindow() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }

        // Advance only 5 seconds — still in the same window
        clock.advance(Duration.ofSeconds(5));
        assertFalse(limiter.tryAcquire("client-1").allowed());
    }

    @Test
    void isolatesClients() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        limiter.tryAcquire("client-a");
        limiter.tryAcquire("client-a");
        assertFalse(limiter.tryAcquire("client-a").allowed());

        assertTrue(limiter.tryAcquire("client-b").allowed());
    }

    @Test
    void remainingCountDecreases() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        RateLimitResult r1 = limiter.tryAcquire("client-1");
        assertEquals(4, r1.remaining());

        RateLimitResult r2 = limiter.tryAcquire("client-1");
        assertEquals(3, r2.remaining());
    }

    @Test
    void retryAfterPointsToWindowEnd() {
        var config = RateLimitConfig.of(1, Duration.ofSeconds(10));
        var limiter = new FixedWindowRateLimiter(config, store, clock);

        limiter.tryAcquire("client-1");

        // Advance 3 seconds into the window
        clock.advance(Duration.ofSeconds(3));
        RateLimitResult denied = limiter.tryAcquire("client-1");

        assertFalse(denied.allowed());
        // Should be ~7 seconds until window resets
        assertTrue(denied.retryAfter().toSeconds() <= 7);
        assertTrue(denied.retryAfter().toSeconds() >= 6);
    }

    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
