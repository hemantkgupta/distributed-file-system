package com.hkg.dfs.common;

/** Object Storage Device identifier. */
public record OsdId(int value) {
    public OsdId {
        if (value < 0) {
            throw new IllegalArgumentException("OsdId cannot be negative: " + value);
        }
    }

    public static OsdId of(int value) {
        return new OsdId(value);
    }
}
