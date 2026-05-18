package com.hkg.dfs.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Settable point-in-time value. */
public final class Gauge {
    private final String name;
    private final AtomicLong value = new AtomicLong();

    public Gauge(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        this.name = name;
    }

    public void set(long v) { value.set(v); }
    public long get() { return value.get(); }
    public String name() { return name; }
}
