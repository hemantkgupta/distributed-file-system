package com.hkg.dfs.common;

/** Monotonically increasing version tag for a PG mapping. */
public record Generation(long value) {
    public Generation {
        if (value < 0) {
            throw new IllegalArgumentException("Generation cannot be negative: " + value);
        }
    }

    public Generation next() {
        return new Generation(value + 1);
    }

    public static Generation initial() {
        return new Generation(0);
    }
}
