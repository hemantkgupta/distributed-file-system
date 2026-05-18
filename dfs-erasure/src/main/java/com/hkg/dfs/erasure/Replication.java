package com.hkg.dfs.erasure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** N-way replication: same byte payload, replicated N times. */
public final class Replication {
    private final int factor;

    public Replication(int factor) {
        if (factor < 1) throw new IllegalArgumentException("factor>=1");
        this.factor = factor;
    }

    public List<byte[]> encode(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("payload");
        List<byte[]> out = new ArrayList<>(factor);
        for (int i = 0; i < factor; i++) out.add(Arrays.copyOf(payload, payload.length));
        return out;
    }

    public byte[] decode(List<byte[]> shards) {
        for (byte[] s : shards) if (s != null) return Arrays.copyOf(s, s.length);
        throw new IllegalStateException("no surviving replica");
    }

    public int factor() { return factor; }
}
