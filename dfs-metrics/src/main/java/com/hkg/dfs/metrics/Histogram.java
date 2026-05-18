package com.hkg.dfs.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-bucket exponentially-bounded histogram. */
public final class Histogram {
    private static final double[] BOUNDS = {0.001, 0.01, 0.1, 1, 10, 100, 1000, 10000};

    private final String name;
    private final AtomicLongArray buckets = new AtomicLongArray(BOUNDS.length + 1);
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sum = new AtomicLong(); // sum stored in micro-units

    public Histogram(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        this.name = name;
    }

    public void record(double value) {
        int idx = BOUNDS.length;
        for (int i = 0; i < BOUNDS.length; i++) {
            if (value <= BOUNDS[i]) { idx = i; break; }
        }
        buckets.incrementAndGet(idx);
        count.incrementAndGet();
        sum.addAndGet((long) (value * 1_000_000));
    }

    public long count() { return count.get(); }
    public double sum() { return sum.get() / 1_000_000.0; }
    public long bucket(int i) { return buckets.get(i); }
    public String name() { return name; }
    public static double[] bounds() { return BOUNDS.clone(); }
    public int bucketCount() { return BOUNDS.length + 1; }
}
