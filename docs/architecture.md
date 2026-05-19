# Architecture — Repository Layout vs Wiki Concepts

> Last reconciled with the repo on 2026-05-20.
>
> How the 15 Java modules map onto the architectural decisions in the CSE wiki, and the dependency graph that lets each one be tested in isolation.

## The two-axis mapping

This repo organises code along two axes:

- **Phase** — the order it was built (foundation → storage backend → control plane → ops). Phases are about *learning order*, not deployment.
- **Concern** — what architectural layer the module implements (placement, consistency, durability, QoS, etc.). Concerns are about *what the code actually does*.

The folder layout reflects concerns. The README, blog, and build flow follow phases.

## The disaggregated control / data plane

The wiki's primary architectural commitment ([`patterns/disaggregated-control-data-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/disaggregated-control-data-plane.md)) cleaves the system into two planes. Every module belongs to one or the other:

```
                       ┌─────────────────────────────────┐
                       │       CONTROL PLANE              │
                       │  (sharded metadata, placement,   │
                       │   monitoring, background ops)    │
                       │                                  │
                       │  dfs-mds            dfs-monitor  │
                       │  dfs-qos            dfs-custodian│
                       │  dfs-placement      dfs-lease    │
                       │  dfs-crush                       │
                       └────────────────┬─────────────────┘
                                        │
                                        │ cluster map, leases,
                                        │ placement decisions
                                        ▼
                       ┌─────────────────────────────────┐
                       │        DATA PLANE                │
                       │  (raw-block storage, EC,         │
                       │   per-OSD QoS, integrity)        │
                       │                                  │
                       │  dfs-storage   dfs-allocator     │
                       │  dfs-erasure                     │
                       └─────────────────────────────────┘

                       Cross-cutting:
                       ┌─────────────────────────────────┐
                       │  dfs-common     (types)          │
                       │  dfs-metrics    (observability)  │
                       │  dfs-security   (crypto-shred)   │
                       │  dfs-node       (composition)    │
                       │  dfs-simulator  (integration)    │
                       └─────────────────────────────────┘
```

## Module dependency graph

```
              dfs-common
                 ▲
                 │ depended on by everything
                 │
   ┌─────────────┼─────────────┬───────────┬────────────┐
   │             │             │           │            │
dfs-crush  dfs-placement  dfs-lease  dfs-allocator  dfs-metrics
   │             │             │           │            │
   └─────┬───────┴─────┬───────┘           │            │
         │             │                   ▼            │
         │             │             dfs-storage        │
         │             │                   ▲            │
         │             │                   │            │
         ▼             ▼             dfs-erasure        │
       dfs-node                            ▲            │
         ▲                                 │            │
         │                                 │            │
         │      ┌──────────────────────────┘            │
         │      │                                       │
         │   dfs-qos ─────► dfs-custodian ◄─── dfs-monitor
         │      ▲                ▲                ▲
         │      │                │                │
         └──────┴────────────────┴────────────────┴──── dfs-mds
                                                          │
                                                          ▼
                                                     dfs-security
                                                          │
                                                          ▼
                                                     dfs-simulator
                                                     (depends on all)
```

A few key rules the graph enforces:

- **`dfs-qos` does NOT depend on `dfs-custodian`**. The scheduler is unaware of repair semantics; the Custodian sits above it and uses it as a generic priority dispatcher. This is what lets `dfs-qos` be tested without spinning up a fake Custodian.
- **`dfs-monitor` does NOT depend on `dfs-mds`**. The monitor knows about OSDs, leases, and durability events — not about file-system inodes.
- **`dfs-simulator` is the only module that depends on everything**. It exists specifically to compose the parts and exercise cross-module sequences in tests.

## Wiki concept → module map

The complete mapping. Each row says: this wiki page is implemented by this Java module, in these key classes.

| Wiki concept | Module | Primary classes |
|---|---|---|
| [`concepts/crush-placement-algorithm`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/crush-placement-algorithm.md) | `dfs-crush` | `Crush`, `StrawSelector`, `CrushMap` |
| [`patterns/hybrid-deterministic-lookup-placement`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/hybrid-deterministic-lookup-placement.md) | `dfs-placement` | `Placement`, `BlockLayer`, `PgLocation` |
| [`concepts/chunk-lease`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/chunk-lease.md) | `dfs-lease` | `LeaseService`, `ChunkLease` |
| [`concepts/extent-sealing`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/extent-sealing.md) | `dfs-lease` | `ExtentService`, `Extent`, `ExtentStatus` |
| [`concepts/bitmap-allocator`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/bitmap-allocator.md) | `dfs-allocator` | `BitmapAllocator`, `Range` |
| [`tradeoffs/posix-fs-vs-raw-block-backend`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/posix-fs-vs-raw-block-backend.md) | `dfs-storage` | `Osd` (BlueStore-style two write paths) |
| [`concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md) | `dfs-erasure` | `Replication`, `ReedSolomon` |
| [`concepts/local-reconstruction-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/local-reconstruction-codes.md) | `dfs-erasure` | `LRC` |
| [`concepts/capability-vector`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/capability-vector.md) | `dfs-mds` | `CapabilityVector`, `MdsCluster.open/recall` |
| [`concepts/dynamic-subtree-partitioning`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dynamic-subtree-partitioning.md) | `dfs-mds` | `SubtreePartitioner` |
| (no single page; tech-spec) | `dfs-monitor` | `Monitor`, `DurabilityEvent`, `OsdStatus` |
| [`concepts/dmclock-qos`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dmclock-qos.md) | `dfs-qos` | `DmClockScheduler`, `QosClass` |
| [`patterns/custodian-background-control-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/custodian-background-control-plane.md) | `dfs-custodian` | `Custodian`, `RepairScanner`, `PriorityClass`, `WorkItem` |
| [`concepts/key-shredding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/key-shredding.md) | `dfs-security` | `Kms`, `DukId`, `KeyDestroyedException` |

The wiki design walkthrough at [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md) is the integration view; this repo's `dfs-simulator` is its code equivalent.

## How the planes communicate

The wiki's description of the plane boundary is "the cluster map". In this repo:

| Boundary primitive | Owned by | Read by |
|---|---|---|
| `PgLocation { Generation, List<OsdId> }` | `dfs-placement.BlockLayer` | `dfs-node` (resolution); future client APIs |
| `ChunkLease { ChunkId, OsdId, expiresAt }` | `dfs-lease.LeaseService` | `dfs-monitor.grantLease`, `dfs-node` |
| Cluster map version | `dfs-monitor.Monitor.publishMap` | all consumers of `Monitor` |
| Durability events | `dfs-monitor.Monitor.drainEvents` | `dfs-custodian.RepairScanner` |
| `WorkItem { PgId, PriorityClass, reason }` | `dfs-custodian.RepairScanner` | `dfs-custodian.Custodian.dispatch` → `dfs-qos.DmClockScheduler.submit` |

These are the only data structures crossing module boundaries. Everything else is private to its module.

## Phase plan — why these phases, in this order

| Phase | Goal | What it teaches |
|---|---|---|
| 1 — Foundation | Make the smallest possible end-to-end PUT work | Why the wiki's "hybrid placement" recommendation actually requires both CRUSH and a Block Layer |
| 2 — Storage backend | Get the kernel out of the data path | The cost of CoW + WAL deferred-write engineering at one OSD's scope |
| 3 — Control plane | Add the operational primitives | Why repair, scrub, rebalance, and QoS can't be retrofitted onto a foreground master |
| 4 — Ops + simulator | Make failure scenarios reproducible | The full-stack integration that proves the parts compose |

The phase order is also the read order. A new reader following the modules in `dfs-common → dfs-crush → ... → dfs-security` sees the architectural argument unfold in the order the wiki presents it.

## Where this departs from production

This repo is a teaching artifact. Five deliberate departures from production cluster file systems:

1. **Storage is in-memory.** `dfs-storage.Osd` uses `ConcurrentSkipListMap<String, byte[]>` instead of a RocksDB-on-BlueFS write path. Real BlueStore is a multi-year engineering project.
2. **Erasure coding is XOR-based.** `dfs-erasure.ReedSolomon` produces XOR parities, not Galois-field RS. The shape (k data + m parity, decode-from-any-k) is correct.
3. **Monitor is single-node.** `dfs-monitor.Monitor` is an in-memory state machine. Real cluster monitors are Paxos quorums.
4. **MDS persistence is in-memory.** `dfs-mds.MdsCluster` uses CHMs; real MDSs durably journal to RADOS or equivalent.
5. **Networking is in-process method calls.** No serialization, no RPC, no failure injection at the wire level.

Each departure is documented in the affected module's page under "Stubs and departures from production".

## Related

- [`getting-started.md`](./getting-started.md) — how to build and exercise the repo
- [`modules/README.md`](./modules/README.md) — per-module deep dive
- [`flows/README.md`](./flows/README.md) — cross-module sequences
- [`decisions/README.md`](./decisions/README.md) — why this shape, not another
