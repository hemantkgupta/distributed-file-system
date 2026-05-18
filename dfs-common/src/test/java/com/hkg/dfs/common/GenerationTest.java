package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationTest {

    @Test
    void initialIsZero() {
        assertThat(Generation.initial().value()).isZero();
    }

    @Test
    void nextIncrements() {
        assertThat(Generation.initial().next().value()).isEqualTo(1);
    }

    @Test
    void rejectsNegative() {
        assertThatThrownBy(() -> new Generation(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
