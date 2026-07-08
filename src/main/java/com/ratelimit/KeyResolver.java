package com.ratelimit;

import java.util.function.Function;

@FunctionalInterface
public interface KeyResolver<R> extends Function<R, String> {

    String resolve(R request);

    @Override
    default String apply(R request) {
        return resolve(request);
    }
}
