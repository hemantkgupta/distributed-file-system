package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgIdTest {

    @Test
    void equalsByValue() {
        assertThat(PgId.of(7)).isEqualTo(PgId.of(7));
    }

    @Test
    void rejectsNegativeId() {
        assertThatThrownBy(() -> PgId.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
