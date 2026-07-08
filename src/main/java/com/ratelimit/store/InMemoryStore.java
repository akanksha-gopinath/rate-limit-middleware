package com.ratelimit.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class InMemoryStore implements RateLimitStore {

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    @Override
    public BucketState computeIfAbsent(String key, BucketState defaultState) {
        return buckets.computeIfAbsent(key, k -> defaultState);
    }

    @Override
    public BucketState updateAtomically(String key, BucketState defaultState, UnaryOperator<BucketState> updater) {
        return buckets.compute(key, (k, existing) -> {
            BucketState current = existing != null ? existing : defaultState;
            return updater.apply(current);
        });
    }

    public void clear() {
        buckets.clear();
    }

    public int size() {
        return buckets.size();
    }
}
