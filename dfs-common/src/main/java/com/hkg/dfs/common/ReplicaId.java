package com.hkg.dfs.common;

/** A replica of a chunk pinned to a specific OSD. */
public record ReplicaId(ChunkId chunkId, OsdId osdId) {
    public ReplicaId {
        if (chunkId == null) throw new IllegalArgumentException("chunkId");
        if (osdId == null) throw new IllegalArgumentException("osdId");
    }
}
