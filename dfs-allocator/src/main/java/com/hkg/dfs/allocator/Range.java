package com.hkg.dfs.allocator;

/** A contiguous range of allocator units: [start, start+length). */
public record Range(long start, long length) {
    public Range {
        if (start < 0) throw new IllegalArgumentException("start");
        if (length <= 0) throw new IllegalArgumentException("length");
    }

    public long endExclusive() {
        return start + length;
    }
}
