package com.ratelimit.store;

import java.util.function.UnaryOperator;

public interface RateLimitStore {

    BucketState computeIfAbsent(String key, BucketState defaultState);

    BucketState updateAtomically(String key, BucketState defaultState, UnaryOperator<BucketState> updater);
}
