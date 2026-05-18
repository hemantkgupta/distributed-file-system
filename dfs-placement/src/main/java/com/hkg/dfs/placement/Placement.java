package com.hkg.dfs.placement;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.PgId;

/**
 * Deterministic object → PG hash. Implements the first half of the block-layer
 * lookup from {@code wiki/concepts/crush-placement-algorithm}.
 */
public final class Placement {
    private final int pgCount;

    public Placement(int pgCount) {
        if (pgCount <= 0) throw new IllegalArgumentException("pgCount > 0");
        this.pgCount = pgCount;
    }

    public PgId objectToPg(ObjectId obj) {
        int h = stableHash(obj.value());
        return PgId.of(Math.floorMod(h, pgCount));
    }

    public int pgCount() { return pgCount; }

    private static int stableHash(String s) {
        // FNV-1a 32-bit
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
