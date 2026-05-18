package com.hkg.dfs.custodian;

import com.hkg.dfs.monitor.DurabilityEvent;

import java.util.ArrayList;
import java.util.List;

/** Pure function from cluster state to a sorted work-item list. */
public final class RepairScanner {

    public List<WorkItem> scan(ClusterState state) {
        List<WorkItem> items = new ArrayList<>();
        for (DurabilityEvent e : state.belowFloor()) {
            items.add(new WorkItem(e.pg(), PriorityClass.CRITICAL_REPAIR,
                    "below durability floor: " + e.currentReplicas() + "/" + e.floor()));
        }
        for (var pg : state.dueForDeepScrub()) {
            items.add(new WorkItem(pg, PriorityClass.DEEP_SCRUB, "deep scrub"));
        }
        for (var pg : state.dueForShallowScrub()) {
            items.add(new WorkItem(pg, PriorityClass.SHALLOW_SCRUB, "shallow scrub"));
        }
        for (var pg : state.needRebalance()) {
            items.add(new WorkItem(pg, PriorityClass.REBALANCE, "rebalance"));
        }
        items.sort((a, b) -> Integer.compare(a.priority().ordinal(), b.priority().ordinal()));
        return items;
    }
}
