package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaIdTest {

    @Test
    void carriesChunkAndOsd() {
        var r = new ReplicaId(ChunkId.of(1), OsdId.of(2));
        assertThat(r.chunkId()).isEqualTo(ChunkId.of(1));
        assertThat(r.osdId()).isEqualTo(OsdId.of(2));
    }

    @Test
    void rejectsNullChunk() {
        assertThatThrownBy(() -> new ReplicaId(null, OsdId.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
