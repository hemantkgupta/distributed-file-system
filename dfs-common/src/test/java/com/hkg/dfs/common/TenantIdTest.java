package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIdTest {

    @Test
    void equalsByValue() {
        assertThat(TenantId.of("t1")).isEqualTo(TenantId.of("t1"));
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> TenantId.of("")).isInstanceOf(IllegalArgumentException.class);
    }
}
