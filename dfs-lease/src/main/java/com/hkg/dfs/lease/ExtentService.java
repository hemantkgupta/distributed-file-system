package com.hkg.dfs.lease;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates append-only extents. On {@link #seal(String)}, subsequent
 * {@link #append(String, byte[])} calls throw. The sealed length is the
 * authoritative durable length used by the block layer.
 * Implements {@code wiki/concepts/extent-sealing}.
 */
public final class ExtentService {
    private final ConcurrentHashMap<String, Extent> extents = new ConcurrentHashMap<>();

    public Extent open(String extentId) {
        Extent e = new Extent(extentId, ExtentStatus.OPEN, 0);
        Extent prev = extents.putIfAbsent(extentId, e);
        if (prev != null) throw new IllegalStateException("extent exists: " + extentId);
        return e;
    }

    public Extent append(String extentId, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        return extents.compute(extentId, (k, cur) -> {
            if (cur == null) throw new IllegalStateException("extent missing: " + extentId);
            return cur.grow(bytes.length);
        });
    }

    public Extent seal(String extentId) {
        return extents.compute(extentId, (k, cur) -> {
            if (cur == null) throw new IllegalStateException("extent missing: " + extentId);
            return cur.seal();
        });
    }

    public long sealedLength(String extentId) {
        Extent e = extents.get(extentId);
        if (e == null) throw new IllegalStateException("extent missing: " + extentId);
        if (e.status() != ExtentStatus.SEALED) {
            throw new IllegalStateException("extent not sealed: " + extentId);
        }
        return e.length();
    }

    public Extent get(String extentId) {
        Extent e = extents.get(extentId);
        if (e == null) throw new IllegalStateException("extent missing: " + extentId);
        return e;
    }
}
