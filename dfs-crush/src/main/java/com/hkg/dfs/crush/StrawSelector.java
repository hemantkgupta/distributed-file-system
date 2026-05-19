package com.hkg.dfs.crush;

import java.util.List;

/**
 * Deterministic weighted child selection: for each candidate, draw a
 * pseudo-random straw of length {@code hash(seed, name, replicaIdx) * weight}
 * and pick the longest. This is the property that lets CRUSH avoid
 * mass-reshuffling when buckets are added or removed
 * ({@code wiki/concepts/crush-placement-algorithm}).
 */
public final class StrawSelector {

    public CrushBucket select(List<CrushBucket> candidates, long seed, int replicaIdx) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no candidates");
        }
        CrushBucket best = null;
        double bestStraw = -Double.MAX_VALUE;
        for (CrushBucket c : candidates) {
            long h = hash(seed, c.name().hashCode(), replicaIdx);
            double u = (double) (h & 0x7FFFFFFFFFFFFFFFL) / Long.MAX_VALUE;
            if (u <= 0.0) u = 1e-15;
            double straw = Math.log(u) / c.weight();
            if (straw > bestStraw) {
                bestStraw = straw;
                best = c;
            }
        }
        return best;
    }

    static long hash(long seed, int nameHash, int replicaIdx) {
        long x = seed * 0xBF58476D1CE4E5B9L + nameHash * 0x94D049BB133111EBL + replicaIdx * 0xD1342543DE82EF95L;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
