package com.ratelimit;

import com.ratelimit.algorithm.LeakyBucketRateLimiter;
import com.ratelimit.algorithm.TokenBucketRateLimiter;
import com.ratelimit.store.RedisStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RedisStoreTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @BeforeAll
    static void checkDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping Redis integration tests");
    }

    private JedisPool pool;
    private RedisStore store;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(redis.getHost(), redis.getFirstMappedPort());
        store = new RedisStore(pool);
        try (var jedis = pool.getResource()) {
            jedis.flushAll();
        }
    }

    @Test
    void tokenBucketAllowsUpToCapacity() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new TokenBucketRateLimiter(config, store);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("redis-client-1").allowed());
        }
        assertFalse(limiter.tryAcquire("redis-client-1").allowed());
    }

    @Test
    void tokenBucketIsolatesClients() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new TokenBucketRateLimiter(config, store);

        limiter.tryAcquire("client-a");
        limiter.tryAcquire("client-a");
        assertFalse(limiter.tryAcquire("client-a").allowed());

        assertTrue(limiter.tryAcquire("client-b").allowed());
    }

    @Test
    void leakyBucketAllowsUpToCapacity() {
        var config = RateLimitConfig.of(5, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("redis-client-2").allowed());
        }
        assertFalse(limiter.tryAcquire("redis-client-2").allowed());
    }

    @Test
    void leakyBucketIsolatesClients() {
        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiter = new LeakyBucketRateLimiter(config, store);

        limiter.tryAcquire("client-x");
        limiter.tryAcquire("client-x");
        assertFalse(limiter.tryAcquire("client-x").allowed());

        assertTrue(limiter.tryAcquire("client-y").allowed());
    }

    @Test
    void tokenBucketConcurrentAccessRespectsCapacity() throws InterruptedException {
        var config = RateLimitConfig.of(50, Duration.ofMinutes(1));
        var limiter = new TokenBucketRateLimiter(config, store);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    RateLimitResult result = limiter.tryAcquire("concurrent-redis-key");
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
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(allowedCount.get() <= 50,
                "Allowed " + allowedCount.get() + " but capacity is 50");
    }

    @Test
    void customPrefixIsolatesNamespaces() {
        var storeA = new RedisStore(pool, "service-a:");
        var storeB = new RedisStore(pool, "service-b:");

        var config = RateLimitConfig.of(2, Duration.ofSeconds(10));
        var limiterA = new TokenBucketRateLimiter(config, storeA);
        var limiterB = new TokenBucketRateLimiter(config, storeB);

        limiterA.tryAcquire("shared-key");
        limiterA.tryAcquire("shared-key");
        assertFalse(limiterA.tryAcquire("shared-key").allowed());

        // Same key, different prefix — still has tokens
        assertTrue(limiterB.tryAcquire("shared-key").allowed());
    }

    @Test
    void statePersistedAcrossLimiterInstances() {
        var config = RateLimitConfig.of(3, Duration.ofSeconds(10));

        var limiter1 = new TokenBucketRateLimiter(config, store);
        limiter1.tryAcquire("persistent-key");
        limiter1.tryAcquire("persistent-key");

        // New limiter instance, same store — should see existing state
        var limiter2 = new TokenBucketRateLimiter(config, store);
        assertTrue(limiter2.tryAcquire("persistent-key").allowed());
        assertFalse(limiter2.tryAcquire("persistent-key").allowed());
    }
}
