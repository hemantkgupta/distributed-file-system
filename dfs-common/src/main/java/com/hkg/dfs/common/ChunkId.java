package com.hkg.dfs.common;

/** Identifier for a fixed-size chunk of an object. */
public record ChunkId(long value) {
    public ChunkId {
        if (value < 0) {
            throw new IllegalArgumentException("ChunkId cannot be negative: " + value);
        }
    }

    public static ChunkId of(long value) {
        return new ChunkId(value);
    }
}
