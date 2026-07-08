package com.ratelimit;

import com.ratelimit.algorithm.LeakyBucketRateLimiter;
import com.ratelimit.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeakyBucketRateLimiterTest {

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
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire("client-1");
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void deniesWhenBucketFull() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client-1");
        }

        RateLimitResult denied = limiter.tryAcquire("client-1");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remaining());
        assertTrue(denied.retryAfter().toMillis() > 0);
    }

    @Test
    void leaksOverTime() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        limiter.tryAcquire("client-1");
        limiter.tryAcquire("client-1");
        assertFalse(limiter.tryAcquire("client-1").allowed());

        // Advance full period — all water leaks out
        clock.advance(Duration.ofSeconds(10));

        RateLimitResult allowed = limiter.tryAcquire("client-1");
        assertTrue(allowed.allowed());
    }

    @Test
    void partialLeak() {
        var config = RateLimitConfig.of(10, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        // Fill bucket completely
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("client-1");
        }
        assertFalse(limiter.tryAcquire("client-1").allowed());

        // Advance 3 seconds — leaks 3 units (leak rate = 10/10s = 1/s)
        clock.advance(Duration.ofSeconds(3));

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("client-1").allowed(),
                    "Request " + (i + 1) + " after partial leak should be allowed");
        }
        assertFalse(limiter.tryAcquire("client-1").allowed());
    }

    @Test
    void isolatesClients() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        limiter.tryAcquire("client-a");
        limiter.tryAcquire("client-a");
        assertFalse(limiter.tryAcquire("client-a").allowed());

        assertTrue(limiter.tryAcquire("client-b").allowed());
    }

    @Test
    void remainingCountDecrements() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store, clock);

        RateLimitResult r1 = limiter.tryAcquire("client-1");
        assertEquals(4, r1.remaining());

        RateLimitResult r2 = limiter.tryAcquire("client-1");
        assertEquals(3, r2.remaining());
    }

    @Test
    void concurrentAccessRespectsCapacity() throws InterruptedException {
        var config = RateLimitConfig.of(100, Duration.ofMinutes(1));
        var limiter = new LeakyBucketRateLimiter(config, store);

        int threadCount = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    RateLimitResult result = limiter.tryAcquire("shared-key");
                    if (result.allowed()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(allowedCount.get() <= 100,
                "Allowed " + allowedCount.get() + " but capacity is 100");
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
