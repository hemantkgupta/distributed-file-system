package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsdIdTest {

    @Test
    void equalsByValue() {
        assertThat(OsdId.of(3)).isEqualTo(OsdId.of(3));
    }

    @Test
    void rejectsNegativeId() {
        assertThatThrownBy(() -> OsdId.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
