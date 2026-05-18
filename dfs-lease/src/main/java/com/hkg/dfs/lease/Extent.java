package com.hkg.dfs.lease;

/**
 * An extent is an append-only run of bytes. Once SEALED, length is frozen
 * and no further appends are permitted. Implements
 * {@code wiki/concepts/extent-sealing}.
 */
public record Extent(String extentId, ExtentStatus status, long length) {
    public Extent {
        if (extentId == null || extentId.isBlank()) throw new IllegalArgumentException("extentId");
        if (length < 0) throw new IllegalArgumentException("length");
    }

    public Extent seal() {
        return new Extent(extentId, ExtentStatus.SEALED, length);
    }

    public Extent grow(long delta) {
        if (status == ExtentStatus.SEALED) {
            throw new IllegalStateException("extent " + extentId + " is sealed");
        }
        if (delta < 0) throw new IllegalArgumentException("delta < 0");
        return new Extent(extentId, status, length + delta);
    }
}
