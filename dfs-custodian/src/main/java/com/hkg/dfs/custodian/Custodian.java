package com.hkg.dfs.custodian;

import com.hkg.dfs.qos.DmClockScheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless control loop. For every scanner work item, dispatches a
 * QoS-throttled job into the underlying {@link DmClockScheduler}. Priority
 * band maps to QoS class so dmClock reservations honour repair urgency.
 */
public final class Custodian {
    public static final String CRITICAL_QOS = "critical-repair";
    public static final String ROUTINE_QOS  = "routine-repair";
    public static final String SCRUB_QOS    = "scrub";
    public static final String REBALANCE_QOS = "rebalance";

    private final DmClockScheduler scheduler;
    private final Map<PriorityClass, String> classMap = new HashMap<>();
    private final Map<String, Integer> dispatched = new ConcurrentHashMap<>();

    public Custodian(DmClockScheduler scheduler) {
        this.scheduler = scheduler;
        classMap.put(PriorityClass.CRITICAL_REPAIR, CRITICAL_QOS);
        classMap.put(PriorityClass.ROUTINE_REPAIR, ROUTINE_QOS);
        classMap.put(PriorityClass.DEEP_SCRUB, SCRUB_QOS);
        classMap.put(PriorityClass.SHALLOW_SCRUB, SCRUB_QOS);
        classMap.put(PriorityClass.REBALANCE, REBALANCE_QOS);
        classMap.put(PriorityClass.TIER_TRANSITION, REBALANCE_QOS);
    }

    public void dispatch(WorkItem item) {
        String cls = classMap.get(item.priority());
        scheduler.submit(cls, () -> dispatched.merge(cls, 1, Integer::sum));
    }

    public int dispatched(String className) {
        return dispatched.getOrDefault(className, 0);
    }
}
