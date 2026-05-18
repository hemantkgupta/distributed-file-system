package com.hkg.dfs.erasure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reed-Solomon-style erasure stub: produces k data blocks and m XOR-based
 * parity blocks. Real RS uses Galois field arithmetic; this stub uses
 * iterated XOR so the structure (k, m, decode-from-any-k) holds without
 * pulling in a math library.
 */
public final class ReedSolomon {
    private final int k;
    private final int m;

    public ReedSolomon(int k, int m) {
        if (k <= 0 || m < 0) throw new IllegalArgumentException("k>0 && m>=0");
        this.k = k;
        this.m = m;
    }

    public List<byte[]> encode(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("payload");
        int blockLen = (payload.length + k - 1) / k;
        List<byte[]> data = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            byte[] b = new byte[blockLen];
            int srcOff = i * blockLen;
            int copy = Math.max(0, Math.min(blockLen, payload.length - srcOff));
            if (copy > 0) System.arraycopy(payload, srcOff, b, 0, copy);
            data.add(b);
        }
        List<byte[]> parity = new ArrayList<>(m);
        for (int j = 0; j < m; j++) {
            byte[] p = new byte[blockLen];
            for (int i = 0; i < k; i++) {
                byte[] di = data.get(i);
                int rot = (i + j) % blockLen == 0 ? 0 : (i + j) % blockLen;
                for (int b = 0; b < blockLen; b++) {
                    p[b] ^= (byte) (di[(b + rot) % blockLen] ^ (i * j));
                }
            }
            parity.add(p);
        }
        List<byte[]> all = new ArrayList<>(k + m);
        all.addAll(data);
        all.addAll(parity);
        return all;
    }

    /**
     * Decode from any k surviving shards. The current stub requires the
     * survivors to include all original k data shards (a full RS
     * implementation would inverse-solve the parity matrix). Throws if
     * fewer than k survivors or if data shards are missing.
     */
    public byte[] decode(List<byte[]> shards, int originalLength) {
        if (shards.size() != k + m) {
            throw new IllegalArgumentException("expected " + (k + m) + " shard slots");
        }
        int survivors = 0;
        for (byte[] s : shards) if (s != null) survivors++;
        if (survivors < k) {
            throw new IllegalStateException("only " + survivors + " survivors; need " + k);
        }
        // Stub: rely on data shards being present
        for (int i = 0; i < k; i++) {
            if (shards.get(i) == null) {
                throw new UnsupportedOperationException("stub cannot reconstruct data shard " + i);
            }
        }
        int blockLen = shards.get(0).length;
        byte[] out = new byte[originalLength];
        int written = 0;
        for (int i = 0; i < k && written < originalLength; i++) {
            int copy = Math.min(blockLen, originalLength - written);
            System.arraycopy(shards.get(i), 0, out, written, copy);
            written += copy;
        }
        return out;
    }

    public int k() { return k; }
    public int m() { return m; }
    public int totalShards() { return k + m; }
}
