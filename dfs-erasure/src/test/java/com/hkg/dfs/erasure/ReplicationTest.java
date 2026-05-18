package com.hkg.dfs.erasure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationTest {

    @Test
    void encodeProducesFactorCopies() {
        Replication r = new Replication(3);
        List<byte[]> shards = r.encode(new byte[]{1, 2, 3});
        assertThat(shards).hasSize(3);
    }

    @Test
    void allShardsEqual() {
        Replication r = new Replication(3);
        List<byte[]> shards = r.encode(new byte[]{1, 2, 3});
        for (byte[] s : shards) assertThat(s).containsExactly(1, 2, 3);
    }

    @Test
    void roundTripRecoversPayload() {
        Replication r = new Replication(3);
        List<byte[]> shards = r.encode(new byte[]{7, 8});
        assertThat(r.decode(shards)).containsExactly(7, 8);
    }

    @Test
    void decodeWithOneSurvivor() {
        Replication r = new Replication(3);
        List<byte[]> shards = new ArrayList<>(r.encode(new byte[]{9}));
        shards.set(0, null);
        shards.set(1, null);
        assertThat(r.decode(shards)).containsExactly(9);
    }

    @Test
    void rejectsFactorZero() {
        assertThatThrownBy(() -> new Replication(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeIsDefensive() {
        Replication r = new Replication(2);
        byte[] src = {1};
        List<byte[]> shards = r.encode(src);
        src[0] = 99;
        for (byte[] s : shards) assertThat(s[0]).isEqualTo((byte) 1);
    }
}
