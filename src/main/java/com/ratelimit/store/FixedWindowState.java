package com.ratelimit.store;

public record FixedWindowState(long requestCount, long windowStartNanos) {

    public static FixedWindowState initial(long windowStartNanos) {
        return new FixedWindowState(0, windowStartNanos);
    }
}
