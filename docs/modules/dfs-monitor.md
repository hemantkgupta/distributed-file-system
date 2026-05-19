# dfs-monitor

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The cluster monitor. Three responsibilities:

- **OSD liveness** — track heartbeats; mark OSDs DOWN after `missThreshold` consecutive misses.
- **Lease management** — delegate `grantLease` / `revokeLease` to `dfs-lease.LeaseService`.
- **Cluster map publication** — receive PG→OSD snapshots, bump `mapVersion`, hold the canonical view.
- **Durability events** — on every `tick()`, compute per-PG live-replica counts; emit `DurabilityEvent` for any PG below the configured floor.

The Custodian consumes these durability events to schedule repair work.

## 2. Wiki anchor

No single wiki concept page; the monitor is described in the design walkthrough [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md) §High-Level Design.

## 3. Public API surface

```java
package com.hkg.dfs.monitor;

public final class Monitor {
    public Monitor(LeaseService leases, int missThreshold, int durabilityFloor);

    public void register(OsdId osd);
    public ChunkLease grantLease(ChunkId chunk, OsdId primary);
    public void revokeLease(ChunkId chunk);
    public synchronized long publishMap(Map<PgId, List<OsdId>> snapshot);
    public long mapVersion();
    public OsdStatus statusOf(OsdId osd);
    public synchronized void heartbeat(OsdId osd);   // resets miss counter
    public synchronized void tick();                  // advance time; emit events
    public synchronized List<DurabilityEvent> drainEvents();
}

public enum OsdStatus { UP, DOWN }

public record DurabilityEvent(PgId pg, int currentReplicas, int floor) {}
```

Source: `dfs-monitor/src/main/java/com/hkg/dfs/monitor/`.

## 4. Internal structure

- **`status`** — `ConcurrentHashMap<OsdId, OsdStatus>`.
- **`missedBeats`** — `ConcurrentHashMap<OsdId, Integer>`. Incremented every `tick()`; reset by `heartbeat()`.
- **`pgMap`** — the published cluster map.
- **`events`** — `ArrayList<DurabilityEvent>` accumulated between `drainEvents` calls. Drain returns and clears.
- **`mapVersion`** — monotonic.

The `tick()` method models simulated time:

```java
public synchronized void tick() {
    for (OsdId o : status.keySet()) {
        int v = missedBeats.getOrDefault(o, 0) + 1;
        missedBeats.put(o, v);
        if (v >= missThreshold) status.put(o, OsdStatus.DOWN);
    }
    emitDurabilityEvents();
}
```

`emitDurabilityEvents` walks `pgMap`, counts UP replicas, and emits a `DurabilityEvent` for any PG below `durabilityFloor`. Events accumulate until `drainEvents` is called.

## 5. Key tests

12 tests in `MonitorTest`.

| Test | Demonstrates |
|---|---|
| `registerSetsUp` | Newly-registered OSD starts UP. |
| `heartbeatKeepsUp` | An OSD that heartbeats every tick stays UP. |
| `missedHeartbeatsMarkDown` | After `missThreshold` ticks without heartbeat, status flips to DOWN. |
| `heartbeatRegistersIfMissing` | Calling `heartbeat` for an unknown OSD registers it as UP. |
| `publishMapIncrementsVersion` | Two `publishMap` calls produce versions N and N+1. |
| `durabilityEventEmittedWhenBelowFloor` | With floor=2 and a PG losing replicas, an event is emitted. |
| `noEventsWhenAboveFloor` | A PG with enough live replicas produces no events. |
| `drainEventsClears` | After drain, the next drain returns empty. |
| `grantLeaseStoresIt` | `grantLease` delegates to `LeaseService.grant`. |

## 6. Where it fits

**Upstream consumers:** `dfs-custodian` (drains durability events to seed repair work); `dfs-simulator`.

**Downstream dependencies:** `dfs-common`, `dfs-lease`.

**The dependency rule:** the monitor delegates lease management to `dfs-lease`. It does NOT know about subtree partitioning, capabilities, or the data plane.

## 7. Stubs and departures from production

- **Single-node, no consensus.** Real cluster monitors are Paxos quorums (Ceph MON cluster, typically 3 or 5 nodes). This module is one in-process object.
- **`tick()` is manual.** A test or the simulator advances time by calling `tick()`. A real monitor runs a heartbeat-deadline timer.
- **No leader election.** With a single-node monitor, the question doesn't arise. With a real quorum, you need a leader to publish maps and grant leases consistently.
- **No persistent state.** A monitor restart loses every heartbeat counter and map version. Production monitors persist to a transactional store and recover via Paxos log replay.
- **No client-side consumers of `mapVersion`.** Real clients fetch the cluster map by version; here, the version is incremented but no one fetches it.
