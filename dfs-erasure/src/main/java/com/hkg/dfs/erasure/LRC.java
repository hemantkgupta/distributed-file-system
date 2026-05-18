package com.hkg.dfs.erasure;

/**
 * Local Reconstruction Codes: k data blocks split into l local groups,
 * each with its own local parity, plus g global parities. Single-block
 * losses are repaired from inside the local group (cheap), multi-block
 * losses fall back to the global parities. Implements
 * {@code wiki/concepts/local-reconstruction-codes}.
 */
public final class LRC {
    private final int k;
    private final int l;
    private final int g;
    private final int groupSize;

    public LRC(int k, int l, int g) {
        if (k <= 0 || l <= 0 || g < 0) {
            throw new IllegalArgumentException("k>0 && l>0 && g>=0");
        }
        if (k % l != 0) {
            throw new IllegalArgumentException("k must be divisible by l");
        }
        this.k = k;
        this.l = l;
        this.g = g;
        this.groupSize = k / l;
    }

    public int localGroupOf(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= k) {
            throw new IndexOutOfBoundsException("blockIndex");
        }
        return blockIndex / groupSize;
    }

    /**
     * Cost of repairing a single failed block index given the surviving
     * block set. If the failure can be repaired locally, cost is the
     * group size (read all peers + local parity). Otherwise cost is k
     * (read all surviving data + global parity).
     */
    public int repairCost(int failedIndex, boolean[] surviving) {
        if (failedIndex < 0 || failedIndex >= k) {
            throw new IndexOutOfBoundsException("failedIndex");
        }
        if (surviving.length != k) {
            throw new IllegalArgumentException("surviving must have length k");
        }
        int group = localGroupOf(failedIndex);
        int groupStart = group * groupSize;
        boolean groupRepairable = true;
        int localPeers = 0;
        for (int i = groupStart; i < groupStart + groupSize; i++) {
            if (i == failedIndex) continue;
            if (!surviving[i]) {
                groupRepairable = false;
                break;
            }
            localPeers++;
        }
        if (groupRepairable) {
            return localPeers + 1; // peers + local parity
        }
        if (g == 0) {
            throw new IllegalStateException("no global parity; cannot recover");
        }
        int surviveCount = 0;
        for (boolean b : surviving) if (b) surviveCount++;
        return Math.min(k, surviveCount) + 1; // global parity
    }

    public int k() { return k; }
    public int l() { return l; }
    public int g() { return g; }
    public int groupSize() { return groupSize; }
}
