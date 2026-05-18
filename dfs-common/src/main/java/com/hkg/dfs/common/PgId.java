package com.hkg.dfs.common;

/**
 * Placement-group identifier. The block layer maps objects to PGs,
 * and PGs to OSD lists; see {@code wiki/concepts/crush-placement-algorithm}.
 */
public record PgId(int value) {
    public PgId {
        if (value < 0) {
            throw new IllegalArgumentException("PgId cannot be negative: " + value);
        }
    }

    public static PgId of(int value) {
        return new PgId(value);
    }
}
