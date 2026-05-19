# dfs-simulator

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The end-to-end integration harness. Composes OSDs, MDS, Monitor, Custodian, and the dmClock scheduler into one `ClusterSim` object so failure-and-recovery scenarios can be exercised in tests without standing up the full repo by hand. This is what proves the modules compose correctly — anything broken at the integration boundary shows up here.

Three scripted scenarios: disk failure, network partition, flash crowd.

## 2. Wiki anchor

No single wiki concept; the simulator is the code equivalent of the wiki design walkthrough at [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md) §Failure Modes & Mitigations.

## 3. Public API surface

```java
package com.hkg.dfs.simulator;

public final class ClusterSim {
    public ClusterSim(int numOsds, int numMds, int numPgs);

    public void simulateDiskFailure();
    public void simulatePartition();
    public void simulateFlashCrowd();

    public void drainScheduler();
    public boolean isHealthy();

    public Monitor monitor();
    public Custodian custodian();
    public DmClockScheduler scheduler();
    public MdsCluster mds();
    public int osdCount();
    public int pgCount();
}
```

Source: `dfs-simulator/src/main/java/com/hkg/dfs/simulator/ClusterSim.java`.

## 4. Internal structure

`ClusterSim`'s constructor wires:

- A `Monitor` with `missThreshold=3`, `durabilityFloor=2`.
- A `DmClockScheduler` with four QoS classes — `critical-repair` (reservation 100), `routine-repair` (10), `scrub` (0), `rebalance` (0).
- A `Custodian` over that scheduler.
- A `RepairScanner` for the Custodian to use.
- An `MdsCluster` with `numMds` subtree assignments.
- `numOsds` OSDs, each registered with the monitor.
- A toy `pgMap` assigning every PG to 3 OSDs round-robin.

The scenario methods:

- **`simulateDiskFailure()`** — `tick()` 5 times to push one OSD over `missThreshold`; drain durability events; scan into work items; dispatch via Custodian; drain the scheduler. Then heartbeat the OSDs back UP.
- **`simulatePartition()`** — half the OSDs miss heartbeats; durability events fire across multiple PGs; dispatch; drain; heal.
- **`simulateFlashCrowd()`** — submit 200 client ops to a `client` QoS class; trigger a synthetic deep scrub; verify the scheduler honours QoS.

`drainScheduler()` calls `scheduler.dispatch()` until it returns null. `isHealthy()` is the assertion the scenario tests use: every OSD UP.

## 5. Key tests

10 tests in `ClusterSimTest`.

| Test | Demonstrates |
|---|---|
| `constructsWithExpectedSizes` | `osdCount()` and `pgCount()` reflect the constructor arguments. |
| `diskFailureConverges` | After `simulateDiskFailure()`, `isHealthy()` returns true. |
| `partitionConverges` | After `simulatePartition()`, the cluster heals back to healthy. |
| `flashCrowdServedWithoutDeadlock` | After draining, no items remain queued on the `client` class. |
| `diskFailureEmitsDurabilityEvents` | Disk failure pushes work through one of the repair/scrub QoS classes. |
| `mdsSubtreeAssigned` | `mds.partitioner().subtreeCount() == numMds`. |
| `rejectsZeroOsds` / `rejectsZeroMds` / `rejectsZeroPgs` | Constructor rejects non-positive sizing arguments. |
| `repeatedScenariosRemainHealthy` | Running all three scenarios back-to-back still ends healthy. |

## 6. Where it fits

**Upstream consumers:** none (this is the top of the dependency tree).

**Downstream dependencies:** every other module — `dfs-common`, `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node`, `dfs-allocator`, `dfs-storage`, `dfs-erasure`, `dfs-mds`, `dfs-monitor`, `dfs-custodian`, `dfs-qos`, `dfs-metrics`, `dfs-security`. Not all of them are exercised by the scenarios yet (see §7); they are wired in `build.gradle` so the simulator is the natural integration site.

**The dependency rule:** the simulator is the only module allowed to depend on the full set. Tests in any other module exercise their own concern; the simulator's tests are the integration boundary.

## 7. Stubs and departures from production

- **No data-plane simulation.** The scenarios exercise the control plane (monitor, custodian, scheduler, MDS). They do not actually write bytes through `dfs-storage` or `dfs-erasure`. Adding those wirings is a natural next step.
- **Toy round-robin PG mapping.** Production placement would use CRUSH. The simulator hard-codes `(p + r) % numOsds` for simplicity.
- **No background timer.** `tick()` is called manually. A real cluster has heartbeat-deadline timers ticking continuously.
- **No actual repair work.** The Custodian dispatches; the scheduler runs ops; the ops are counter increments. No actual data move.
- **No multi-tenant test.** The flash-crowd scenario uses a single `client` class. A real multi-tenant test would have several tenant classes contending.
