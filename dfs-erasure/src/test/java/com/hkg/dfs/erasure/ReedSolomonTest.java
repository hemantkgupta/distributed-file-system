package com.hkg.dfs.erasure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReedSolomonTest {

    @Test
    void encodeReturnsKplusMShards() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        List<byte[]> shards = rs.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThat(shards).hasSize(6);
    }

    @Test
    void allShardsSameLength() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        List<byte[]> shards = rs.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        int len = shards.get(0).length;
        for (byte[] s : shards) assertThat(s.length).isEqualTo(len);
    }

    @Test
    void decodeRoundTrip() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};
        List<byte[]> shards = rs.encode(payload);
        assertThat(rs.decode(shards, payload.length)).containsExactly(payload);
    }

    @Test
    void decodeWithParityLoss() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        byte[] payload = {10, 20, 30, 40};
        List<byte[]> shards = new ArrayList<>(rs.encode(payload));
        shards.set(4, null); // lose one parity
        shards.set(5, null); // lose second parity
        assertThat(rs.decode(shards, payload.length)).containsExactly(payload);
    }

    @Test
    void decodeFailsWithTooFewSurvivors() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        List<byte[]> shards = new ArrayList<>(rs.encode(new byte[]{1, 2, 3, 4}));
        shards.set(0, null);
        shards.set(1, null);
        shards.set(2, null);
        assertThatThrownBy(() -> rs.decode(shards, 4))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidKM() {
        assertThatThrownBy(() -> new ReedSolomon(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReedSolomon(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalShardsIsKplusM() {
        assertThat(new ReedSolomon(10, 4).totalShards()).isEqualTo(14);
    }
}
