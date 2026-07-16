package com.ratelimit;

import com.ratelimit.algorithm.SlidingWindowLogRateLimiter;
import com.ratelimit.store.InMemorySlidingWindowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowLogRateLimiterTest {

    private InMemorySlidingWindowStore store;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new InMemorySlidingWindowStore();
        clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void allowsRequestsUpToCapacity() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire("client-1");
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void deniesAfterCapacityExhausted() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }

        RateLimitResult denied = limiter.tryAcquire("client-1");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remaining());
        assertTrue(denied.retryAfter().toNanos() > 0);
    }

    @Test
    void slidesWindowContinuously() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        // Use all capacity
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }
        assertFalse(limiter.tryAcquire("client-1").allowed());

        // Advance 5 seconds — still within window of first requests
        clock.advance(Duration.ofSeconds(5));
        assertFalse(limiter.tryAcquire("client-1").allowed());

        // Advance past the full window (10s total from first request)
        clock.advance(Duration.ofSeconds(6));
        // Now the original 3 requests have slid out of the window
        RateLimitResult allowed = limiter.tryAcquire("client-1");
        assertTrue(allowed.allowed());
    }

    @Test
    void noFixedWindowBoundaryProblem() {
        // Unlike fixed window, sliding window doesn't reset at boundaries
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        // Make 3 requests at t=8s
        clock.advance(Duration.ofSeconds(8));
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }

        // At t=11s (3s later), those requests are still within the 10s window
        clock.advance(Duration.ofSeconds(3));
        assertFalse(limiter.tryAcquire("client-1").allowed());
    }

    @Test
    void isolatesClients() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        limiter.tryAcquire("client-a");
        limiter.tryAcquire("client-a");
        assertFalse(limiter.tryAcquire("client-a").allowed());

        assertTrue(limiter.tryAcquire("client-b").allowed());
    }

    @Test
    void remainingCountDecreases() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new SlidingWindowLogRateLimiter(config, store, clock);

        RateLimitResult r1 = limiter.tryAcquire("client-1");
        assertEquals(4, r1.remaining());

        RateLimitResult r2 = limiter.tryAcquire("client-1");
        assertEquals(3, r2.remaining());
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
