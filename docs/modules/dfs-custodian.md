# dfs-custodian

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The stateless background control loop. Two collaborators:

- **`RepairScanner.scan(state)`** — pure function turning a cluster-state snapshot into a sorted list of `WorkItem`s. Inputs include the Monitor's durability events plus operator-flagged PGs (due for deep scrub, due for shallow scrub, needing rebalance).
- **`Custodian.dispatch(item)`** — maps a `PriorityClass` to a dmClock class name and submits to the scheduler.

The pattern is the wiki's Custodian: scrub, repair, rebalance, GC, and tier transitions all run through one stateless control loop that's separate from the foreground placement plane.

## 2. Wiki anchor

[`wiki/patterns/custodian-background-control-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/custodian-background-control-plane.md).

## 3. Public API surface

```java
package com.hkg.dfs.custodian;

public final class Custodian {
    public static final String CRITICAL_QOS  = "critical-repair";
    public static final String ROUTINE_QOS   = "routine-repair";
    public static final String SCRUB_QOS     = "scrub";
    public static final String REBALANCE_QOS = "rebalance";

    public Custodian(DmClockScheduler scheduler);
    public void dispatch(WorkItem item);
    public int dispatched(String className);   // test hook
}

public final class RepairScanner {
    public List<WorkItem> scan(ClusterState state);   // returns sorted by priority
}

public record WorkItem(PgId pg, PriorityClass priority, String description) {}

public enum PriorityClass {
    CRITICAL_REPAIR, ROUTINE_REPAIR, DEEP_SCRUB, SHALLOW_SCRUB, REBALANCE, TIER_TRANSITION
}

public record ClusterState(
    Set<PgId> allPgs,
    List<DurabilityEvent> belowFloor,
    Set<PgId> dueForDeepScrub,
    Set<PgId> dueForShallowScrub,
    Set<PgId> needRebalance
) {}
```

Source: `dfs-custodian/src/main/java/com/hkg/dfs/custodian/`.

## 4. Internal structure

- **`RepairScanner.scan(state)`** — walks each of the five lists in `ClusterState` and produces a `WorkItem` per entry, with the appropriate `PriorityClass`. Then sorts by `priority.ordinal()` ascending (so CRITICAL_REPAIR comes first).
- **`Custodian.classMap`** — `Map<PriorityClass, String>` mapping each priority class to a dmClock class name. Both `DEEP_SCRUB` and `SHALLOW_SCRUB` map to `SCRUB_QOS`; both `REBALANCE` and `TIER_TRANSITION` map to `REBALANCE_QOS` — coarser dmClock granularity than the scanner's priority taxonomy.
- **`Custodian.dispatched`** — `Map<String, Integer>` test hook counting dispatches per class.

The dispatcher:

```java
public void dispatch(WorkItem item) {
    String cls = classMap.get(item.priority());
    scheduler.submit(cls, () -> dispatched.merge(cls, 1, Integer::sum));
}
```

The submitted runnable is a noop counter increment; a real implementation would call into the OSD/storage layer to actually do the repair.

## 5. Key tests

18 tests across `RepairScannerTest` (9) and `CustodianTest` (9).

| Test | Demonstrates |
|---|---|
| `durabilityEventBecomesCriticalRepair` | Each `DurabilityEvent` becomes a `CRITICAL_REPAIR` work item. |
| `criticalRepairSortsAheadOfScrub` | Sorted output places CRITICAL_REPAIR before DEEP_SCRUB. |
| `deepScrubBeforeShallowScrub` | Ordinal-based sort: DEEP_SCRUB ahead of SHALLOW_SCRUB. |
| `priorityOrderingIsConsistent` | Output list is non-decreasing by `priority.ordinal()`. |
| `dispatchCriticalSubmitsToCriticalClass` | After `dispatch(criticalItem)`, the scheduler's `critical-repair` queue has one op. |
| `scrubItemsGoToScrubClass` | Both DEEP_SCRUB and SHALLOW_SCRUB priorities map to the `scrub` QoS class. |
| `tierTransitionUsesRebalanceClass` | Both REBALANCE and TIER_TRANSITION map to the `rebalance` QoS class. |
| `scannerThenDispatchEndToEnd` | Scanner output, when dispatched through the Custodian and run by the scheduler, increments the dispatched counter. |
| `manyItemsQueueIndependently` | 10 ROUTINE_REPAIR items produce queueDepth=10 on the `routine-repair` class. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator` (drives end-to-end failure-and-recovery scenarios).

**Downstream dependencies:** `dfs-common`, `dfs-monitor` (`DurabilityEvent`), `dfs-qos` (the scheduler it submits to).

**The dependency rule:** the Custodian is the *only* module besides the simulator that depends on both the monitor and the QoS scheduler. The scheduler doesn't know the Custodian exists; the monitor emits events without knowing who consumes them.

## 7. Stubs and departures from production

- **`Runnable` is a counter increment, not real repair.** A real Custodian dispatcher reads the failed PG's chunks, finds surviving replicas, allocates new destinations, and orchestrates the actual data move. None of that machinery is here.
- **`ClusterState` is pull-snapshot, not subscription.** Real Custodians subscribe to event streams from the monitor and react incrementally. Here, the scanner takes a one-shot snapshot.
- **No priority elevation policy.** The wiki's "auto-elevate recovery when PG below floor" logic isn't here — the static priority comes from where the PG appeared in the state (`belowFloor` → CRITICAL_REPAIR is the entire mechanism).
- **No work-item deduplication.** If the same PG appears in `belowFloor` across two scans, two CRITICAL_REPAIR items are emitted. A real implementation tracks in-flight work to avoid duplicate dispatches.
- **No backpressure on the scheduler.** A real Custodian throttles submissions when the scheduler's queue depth exceeds a threshold.
