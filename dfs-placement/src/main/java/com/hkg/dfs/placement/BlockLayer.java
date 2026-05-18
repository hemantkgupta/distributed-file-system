package com.hkg.dfs.placement;

import com.hkg.dfs.common.Generation;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PG → location table. Supports topology changes via
 * {@link #updateLocation(PgId, List)}; each update bumps the generation,
 * which prevents clients reading from a stale view from succeeding silently.
 * Implements the block-layer lookup table described in
 * {@code wiki/my-explanations/design-distributed-file-system}.
 */
public final class BlockLayer {
    private final ConcurrentHashMap<PgId, PgLocation> table = new ConcurrentHashMap<>();

    public PgLocation putInitial(PgId pg, List<OsdId> osds) {
        PgLocation loc = new PgLocation(Generation.initial(), osds);
        PgLocation prev = table.putIfAbsent(pg, loc);
        if (prev != null) {
            throw new IllegalStateException("pg already exists: " + pg);
        }
        return loc;
    }

    public Optional<PgLocation> lookup(PgId pg) {
        return Optional.ofNullable(table.get(pg));
    }

    public PgLocation updateLocation(PgId pg, List<OsdId> newOsds) {
        return table.compute(pg, (k, cur) -> {
            if (cur == null) throw new IllegalStateException("pg not initialised: " + pg);
            return new PgLocation(cur.generation().next(), newOsds);
        });
    }

    public int size() { return table.size(); }
}
