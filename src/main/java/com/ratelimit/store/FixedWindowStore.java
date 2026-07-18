package com.ratelimit.store;

import java.util.function.UnaryOperator;

public interface FixedWindowStore {

    FixedWindowState computeIfAbsent(String key, FixedWindowState defaultState);

    FixedWindowState updateAtomically(String key, FixedWindowState defaultState, UnaryOperator<FixedWindowState> updater);
}
