package com.ratelimit.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class InMemoryFixedWindowStore implements FixedWindowStore {

    private final ConcurrentHashMap<String, FixedWindowState> windows = new ConcurrentHashMap<>();

    @Override
    public FixedWindowState computeIfAbsent(String key, FixedWindowState defaultState) {
        return windows.computeIfAbsent(key, k -> defaultState);
    }

    @Override
    public FixedWindowState updateAtomically(String key, FixedWindowState defaultState, UnaryOperator<FixedWindowState> updater) {
        return windows.compute(key, (k, existing) -> {
            FixedWindowState current = existing != null ? existing : defaultState;
            return updater.apply(current);
        });
    }

    public void clear() {
        windows.clear();
    }

    public int size() {
        return windows.size();
    }
}
