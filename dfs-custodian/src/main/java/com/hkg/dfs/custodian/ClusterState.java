package com.hkg.dfs.custodian;

import com.hkg.dfs.common.PgId;
import com.hkg.dfs.monitor.DurabilityEvent;

import java.util.List;
import java.util.Set;

/** Read-only snapshot the scanner consults. */
public record ClusterState(
        Set<PgId> allPgs,
        List<DurabilityEvent> belowFloor,
        Set<PgId> dueForDeepScrub,
        Set<PgId> dueForShallowScrub,
        Set<PgId> needRebalance
) {
    public ClusterState {
        allPgs = Set.copyOf(allPgs);
        belowFloor = List.copyOf(belowFloor);
        dueForDeepScrub = Set.copyOf(dueForDeepScrub);
        dueForShallowScrub = Set.copyOf(dueForShallowScrub);
        needRebalance = Set.copyOf(needRebalance);
    }
}
