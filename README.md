# Distributed File System

A Java 17 multi-module reference implementation of a Ceph/GFS-class exabyte distributed file system, built around CRUSH placement, BlueStore-style OSDs, a Custodian-driven control plane, and dmClock QoS.

Companion code for the [`distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system.md) blog post in the CSE wiki.

## Status

**All 17 checkpoints across 4 phases complete.** 13 modules.

The phase plan:

**Phase 1 — Foundation:**
* **CP1** — `dfs-common`: immutable foundational types (`ObjectId`, `ChunkId`, `PgId`, `OsdId`, ...)
* **CP2** — `dfs-crush`: CRUSH-style hierarchical bucket walk + straw selector
* **CP3** — `dfs-placement`: deterministic object → PG → OSD lookup
* **CP4** — `dfs-lease`: chunk leases + extent sealing
* **CP5** — `dfs-node`: end-to-end put composing the foundation pieces

**Phase 2 — Storage backend:**
* **CP6** — `dfs-allocator`: bitmap allocator with L1 summary
* **CP7** — `dfs-storage`: BlueStore-style OSD (CoW + WAL + CRC32c)
* **CP8** — `dfs-erasure`: replication + RS stub + LRC group structure

**Phase 3 — Control plane:**
* **CP10** — `dfs-mds`: metadata server with capability vectors + subtree partitioning
* **CP11** — `dfs-monitor`: cluster monitor, heartbeats, durability watcher
* **CP12** — `dfs-custodian`: stateless repair / scrub / rebalance scanner
* **CP13** — `dfs-qos`: dmClock scheduler with Reservation/Weight/Limit phases

**Phase 4 — Ops, simulator, security, deploy:**
* **CP14** — `dfs-simulator`: end-to-end cluster simulator
* **CP15** — `dfs-metrics`: Counter / Gauge / Histogram + Prometheus exporter
* **CP16** — `dfs-security`: KMS-style crypto-shredding
* **CP17** — `deploy/k8s/`: Deployments, HPA, NetworkPolicy, PDB, ServiceMonitor

## Build

Requires JDK 17+.

```sh
./gradlew build
./gradlew :dfs-common:test
```

## Architectural Anchors

The implementation follows the engineering decisions captured in the wiki:

- **CRUSH placement** — [[crush-placement-algorithm]]
- **Bitmap allocator** — [[bitmap-allocator]]
- **Extent sealing** — [[extent-sealing]]
- **Chunk lease** — [[chunk-lease]]
- **dmClock QoS** — [[dmclock-qos]]
- **Capability vectors** — [[capability-vector]]
- **LRC erasure** — [[local-reconstruction-codes]]

## License

Internal reference implementation; not for external distribution.
