package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkIdTest {

    @Test
    void equalsByValue() {
        assertThat(ChunkId.of(1)).isEqualTo(ChunkId.of(1));
    }

    @Test
    void rejectsNegativeId() {
        assertThatThrownBy(() -> ChunkId.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsZero() {
        assertThat(ChunkId.of(0).value()).isZero();
    }
}
