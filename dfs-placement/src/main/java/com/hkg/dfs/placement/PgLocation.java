package com.hkg.dfs.placement;

import com.hkg.dfs.common.Generation;
import com.hkg.dfs.common.OsdId;

import java.util.List;

/** A PG's current mapping: replication set + generation. */
public record PgLocation(Generation generation, List<OsdId> osds) {
    public PgLocation {
        if (osds == null || osds.isEmpty()) {
            throw new IllegalArgumentException("osds must be non-empty");
        }
        osds = List.copyOf(osds);
    }

    public OsdId primary() { return osds.get(0); }
}
