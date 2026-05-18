package com.hkg.dfs.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BytesTest {

    @Test
    void copyIsDefensive() {
        byte[] src = {1, 2, 3};
        Bytes b = Bytes.copyOf(src);
        src[0] = 99;
        assertThat(b.at(0)).isEqualTo((byte) 1);
    }

    @Test
    void toByteArrayIsDefensive() {
        Bytes b = Bytes.copyOf(new byte[]{4, 5});
        byte[] out = b.toByteArray();
        out[0] = 99;
        assertThat(b.at(0)).isEqualTo((byte) 4);
    }

    @Test
    void equalsByContent() {
        assertThat(Bytes.copyOf(new byte[]{1, 2})).isEqualTo(Bytes.copyOf(new byte[]{1, 2}));
    }

    @Test
    void lengthReturnsSize() {
        assertThat(Bytes.copyOf(new byte[]{1, 2, 3}).length()).isEqualTo(3);
    }
}
