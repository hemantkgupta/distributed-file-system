package com.hkg.dfs.lease;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;

import java.time.Instant;

/**
 * A primary-OSD lease for a chunk. While the lease is valid, only the
 * primary may order writes for that chunk. Implements
 * {@code wiki/concepts/chunk-lease}.
 */
public record ChunkLease(ChunkId chunkId, OsdId primaryOsd, Instant expiresAt) {
    public ChunkLease {
        if (chunkId == null) throw new IllegalArgumentException("chunkId");
        if (primaryOsd == null) throw new IllegalArgumentException("primaryOsd");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt");
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
