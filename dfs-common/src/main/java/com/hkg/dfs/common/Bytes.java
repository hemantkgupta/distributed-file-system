package com.hkg.dfs.common;

import java.util.Arrays;

/**
 * Immutable wrapper around a byte array. Defensively copies on construction
 * and on read so that callers cannot mutate cached payloads.
 */
public final class Bytes {
    private final byte[] data;

    private Bytes(byte[] data) {
        this.data = data;
    }

    public static Bytes copyOf(byte[] src) {
        if (src == null) throw new IllegalArgumentException("src");
        return new Bytes(Arrays.copyOf(src, src.length));
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, data.length);
    }

    public int length() {
        return data.length;
    }

    public byte at(int i) {
        return data[i];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes other && Arrays.equals(this.data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
