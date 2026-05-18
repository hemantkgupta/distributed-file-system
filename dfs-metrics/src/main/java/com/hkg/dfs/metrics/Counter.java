package com.hkg.dfs.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonically increasing counter. */
public final class Counter {
    private final String name;
    private final AtomicLong value = new AtomicLong();

    public Counter(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        this.name = name;
    }

    public void inc() { value.incrementAndGet(); }
    public void inc(long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta>=0");
        value.addAndGet(delta);
    }
    public long get() { return value.get(); }
    public String name() { return name; }
}
