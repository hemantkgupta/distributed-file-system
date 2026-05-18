package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectIdTest {

    @Test
    void equalsByValue() {
        assertThat(ObjectId.of("a")).isEqualTo(ObjectId.of("a"));
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> ObjectId.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ObjectId.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exposesValue() {
        assertThat(ObjectId.of("abc").value()).isEqualTo("abc");
    }
}
