package com.ratelimit;

import com.ratelimit.algorithm.LeakyBucketRateLimiter;
import com.ratelimit.algorithm.TokenBucketRateLimiter;
import com.ratelimit.store.InMemoryStore;
import com.ratelimit.store.RateLimitStore;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RateLimitingMiddleware<R, S> {

    private final RateLimiter limiter;
    private final KeyResolver<R> keyResolver;
    private final Function<R, S> handler;
    private final Function<RateLimitResult, S> deniedHandler;

    private RateLimitingMiddleware(RateLimiter limiter,
                                   KeyResolver<R> keyResolver,
                                   Function<R, S> handler,
                                   Function<RateLimitResult, S> deniedHandler) {
        this.limiter = limiter;
        this.keyResolver = keyResolver;
        this.handler = handler;
        this.deniedHandler = deniedHandler;
    }

    public S handle(R request) {
        String key = keyResolver.resolve(request);
        RateLimitResult result = limiter.tryAcquire(key);

        if (result.allowed()) {
            return handler.apply(request);
        }
        return deniedHandler.apply(result);
    }

    public RateLimitResult check(String key) {
        return limiter.tryAcquire(key);
    }

    public RateLimiter rateLimiter() {
        return limiter;
    }

    public static <R, S> Builder<R, S> builder() {
        return new Builder<>();
    }

    public static final class Builder<R, S> {
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private RateLimitConfig config;
        private RateLimitStore store;
        private Clock clock = Clock.systemUTC();
        private KeyResolver<R> keyResolver;
        private Function<R, S> handler;
        private Function<RateLimitResult, S> deniedHandler;

        public Builder<R, S> algorithm(Algorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm);
            return this;
        }

        public Builder<R, S> config(RateLimitConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        public Builder<R, S> store(RateLimitStore store) {
            this.store = Objects.requireNonNull(store);
            return this;
        }

        public Builder<R, S> clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        public Builder<R, S> keyResolver(KeyResolver<R> keyResolver) {
            this.keyResolver = Objects.requireNonNull(keyResolver);
            return this;
        }

        public Builder<R, S> handler(Function<R, S> handler) {
            this.handler = Objects.requireNonNull(handler);
            return this;
        }

        public Builder<R, S> deniedHandler(Function<RateLimitResult, S> deniedHandler) {
            this.deniedHandler = Objects.requireNonNull(deniedHandler);
            return this;
        }

        public RateLimitingMiddleware<R, S> build() {
            Objects.requireNonNull(config, "config is required");
            if (store == null) {
                store = new InMemoryStore();
            }

            RateLimiter limiter = switch (algorithm) {
                case TOKEN_BUCKET -> new TokenBucketRateLimiter(config, store, clock);
                case LEAKY_BUCKET -> new LeakyBucketRateLimiter(config, store, clock);
            };

            return new RateLimitingMiddleware<>(limiter, keyResolver, handler, deniedHandler);
        }
    }
}
