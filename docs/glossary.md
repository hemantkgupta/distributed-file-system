# Glossary

> Last reconciled with the repo on 2026-05-20. Every term that appears more than twice across the docs and isn't obvious to a Principal SWE joining cold should be here. Definitions are 1–3 sentences; long-form belongs in the wiki concept pages.

## A

### Allocation unit
The fixed size of a block tracked by the bitmap allocator. 4 KB on SSD, 64 KB on HDD in BlueStore; configurable in `dfs-allocator.BitmapAllocator`.

### ABS checkpointing
Asynchronous Barrier Snapshotting — not used in this repo (no Flink). Mentioned only in the wiki.

### AES-GCM
The authenticated encryption mode `dfs-security.Kms` uses for crypto-shredding (`AES/GCM/NoPadding`, 128-bit tag, 96-bit IV).

## B

### Bitmap allocator
Free-space tracker. One bit per allocation unit; L1 summary bitmap for skip-fast-forward. Implemented in `dfs-allocator.BitmapAllocator`. See [`concepts/bitmap-allocator`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/bitmap-allocator.md).

### BlueStore
Ceph's raw-block user-space storage backend. This repo's `dfs-storage.Osd` is a teaching-grade approximation. See [`tradeoffs/posix-fs-vs-raw-block-backend`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/posix-fs-vs-raw-block-backend.md).

### Block Layer
The lookup tier mapping a placement group to its physical OSDs. Implemented in `dfs-placement.BlockLayer`. See [`patterns/hybrid-deterministic-lookup-placement`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/hybrid-deterministic-lookup-placement.md).

### Bytes
`dfs-common.Bytes` — immutable byte-array wrapper that defensively copies on construction and read.

## C

### Capability vector (cap)
Fine-grained MDS delegation allowing a client to cache and buffer locally. `dfs-mds.CapabilityVector`. See [`concepts/capability-vector`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/capability-vector.md).

### ChunkId
`dfs-common.ChunkId(long)` — identifier for a fixed-size chunk of an object.

### Chunk lease
Time-bounded grant from the monitor to a primary OSD authorising it to serialise writes for a chunk. `dfs-lease.LeaseService`. See [`concepts/chunk-lease`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/chunk-lease.md).

### ClayCodes
MSR-optimal erasure code. Not implemented in this repo (only `Replication`, `ReedSolomon`, `LRC`). Mentioned in the wiki at [`concepts/clay-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/clay-codes.md).

### Cluster map
The plane-boundary data structure the monitor publishes via `dfs-monitor.Monitor.publishMap`. Versioned by `mapVersion`.

### Control plane
The set of modules that own placement decisions, leases, monitoring, and background work: `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-mds`, `dfs-monitor`, `dfs-qos`, `dfs-custodian`.

### Copy-on-write (CoW)
Large-write path in `dfs-storage.Osd.writeLarge`: write to a fresh extent slot, then commit the metadata transaction linking it to the object. Avoids the journaling double-write of POSIX file systems.

### CRC32c
Castagnoli polynomial CRC. `dfs-storage.Osd` stores one per blob and verifies on read.

### CRUSH
Controlled Replication Under Scalable Hashing. Pseudo-random deterministic placement function from Ceph. `dfs-crush.Crush.place(...)`. See [`concepts/crush-placement-algorithm`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/crush-placement-algorithm.md).

### Custodian
Stateless background control loop driving scrub/repair/rebalance. `dfs-custodian.Custodian`. See [`patterns/custodian-background-control-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/custodian-background-control-plane.md).

## D

### Data plane
The set of modules that own bytes: `dfs-allocator`, `dfs-storage`, `dfs-erasure`. Receives placement decisions and writes/reads bytes.

### dmClock
Multi-tenant proportional-share I/O scheduler. `dfs-qos.DmClockScheduler`. Three tags per class: reservation, weight, limit. See [`concepts/dmclock-qos`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dmclock-qos.md).

### DukId
`dfs-security.DukId(TenantId, ObjectId)` — identifier of a Data Unique Key in the KMS for crypto-shredding.

### Durability event
`dfs-monitor.DurabilityEvent(PgId, currentReplicas, floor)` — emitted when a placement group's live-replica count falls below the configured floor.

### Durability floor
The configured minimum replica count below which a placement group triggers a durability event. Held in `Monitor` constructor argument `durabilityFloor`.

### Dynamic subtree partitioning
Migration of authority for a directory subtree between MDS shards based on load. `dfs-mds.SubtreePartitioner`. See [`concepts/dynamic-subtree-partitioning`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dynamic-subtree-partitioning.md).

## E

### Erasure coding (EC)
A family of redundancy schemes (Reed-Solomon, LRC, ClayCodes) implemented (some as stubs) in `dfs-erasure`. See [`concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md).

### Extent
`dfs-lease.Extent(extentId, status, length)` — append-only byte sequence. Status is `OPEN` or `SEALED`.

### Extent sealing
Closing an extent at its last universally-committed length on primary failure. Future writes roll forward to a new extent. `dfs-lease.ExtentService.seal`. See [`concepts/extent-sealing`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/extent-sealing.md).

## F

### Failure domain
A topology level in the CRUSH map that placement must spread across (rack, host, AZ). `dfs-crush.BucketType`.

### Foundation phase
Phase 1 of the build plan: `dfs-common`, `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node`. Makes the minimal end-to-end PUT work.

## G

### Generation
`dfs-common.Generation(long)` — monotonic version tag for a PG mapping. Bumped on every BlockLayer update.

### Global parity
In an LRC `(k, l, g)` scheme: a parity block computed over all k data blocks. Used when local-group repair is insufficient.

## H

### Heartbeat
`dfs-monitor.Monitor.heartbeat(osdId)` — an OSD's signal of liveness. Missing `missThreshold` consecutive heartbeats marks the OSD `DOWN`.

### Hybrid placement
Two-tier placement: deterministic hash to a PG (cheap, local computation) + lookup of PG → OSDs in a small KV store (operationally smooth). See `dfs-placement.Placement` + `dfs-placement.BlockLayer`.

## I

### Idempotency
Not used in this repo (no Kafka redelivery). The closest analog is `LeaseService.grant` being effectively idempotent within a lease term.

### Implementations section
Every wiki concept page has an `## Implementations` section linking to this repo's module. The mapping is bidirectional.

### Inode
`dfs-mds.Inode(inodeId, parentId, name, InodeType)` — file-system metadata record. `InodeType` is `FILE` or `DIR`.

## K

### KMS
Key Management Service. `dfs-security.Kms` — in-process AES-GCM implementation with key destruction = crypto-shredding.

### Key shredding
The pattern of encrypting data with a per-subject key and destroying the key to satisfy GDPR right-to-be-forgotten. `dfs-security.Kms.destroyDuk`. See [`concepts/key-shredding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/key-shredding.md).

## L

### LRC (Local Reconstruction Codes)
EC scheme that adds local parity groups so single-block repair stays inside one rack-aware group. `dfs-erasure.LRC`. See [`concepts/local-reconstruction-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/local-reconstruction-codes.md).

### Lease
See **chunk lease**. The dfs-lease module exposes `grant`, `renew`, `revoke`, `get`.

### Limit (dmClock tag)
The hard ceiling on a class's I/O rate. Set per `QosClass`.

### Local parity group
In LRC: a subset of k data blocks plus one parity covering only that subset. Repairing a single failed data block reads from inside the group.

## M

### MDS (Metadata Server)
Owner of POSIX namespace + capability vectors. `dfs-mds.MdsCluster`.

### Module
A Gradle subproject. The repo has 15 (`dfs-common`, `dfs-crush`, etc.). Each has its own `build.gradle`, `src/main`, `src/test`.

### Monitor
Cluster-monitor abstraction: tracks OSD heartbeats, grants leases, publishes maps, emits durability events. `dfs-monitor.Monitor`.

### MSR codes
Minimum Storage Regenerating codes. ClayCodes are MSR-optimal; not implemented in this repo.

## N

### NodeApi
`dfs-node.NodeApi.put(obj, bytes)` — the end-to-end composition of CRUSH + placement + lease + extent that an integration test would exercise.

## O

### OSD (Object Storage Device)
The storage daemon: owns the raw block device, tracks free space, serves reads/writes. `dfs-storage.Osd`.

### OsdId
`dfs-common.OsdId(int)` — identifier.

## P

### PG (Placement Group)
A logical bucket of objects. The hybrid placement scheme: object → PG (hash) → OSDs (KV lookup).

### PgId
`dfs-common.PgId(int)` — identifier.

### PgLocation
`dfs-placement.PgLocation(Generation, List<OsdId>)` — what a PG currently maps to, with a version tag.

### Phase
The build order: 1=foundation, 2=storage, 3=control, 4=ops. Not a runtime concept.

### POSIX
The file-system semantics this repo's `dfs-mds` simulates: hierarchical namespace, inodes, dentries, capabilities for close-to-open consistency.

### PriorityClass
`dfs-custodian.PriorityClass` enum: CRITICAL_REPAIR > ROUTINE_REPAIR > DEEP_SCRUB > SHALLOW_SCRUB > REBALANCE > TIER_TRANSITION.

### Prometheus exporter
`dfs-metrics.PrometheusExporter.expose()` — plaintext exposition format for the `Counter`, `Gauge`, `Histogram` primitives.

## Q

### QosClass
`dfs-qos.QosClass(name, reservation, weight, limit)` — the three-knob multi-tenant scheduler parameters.

## R

### Range
`dfs-allocator.Range(start, length)` — a contiguous run of allocation units.

### Range scan
Not implemented in this repo.

### Recall (capability)
`dfs-mds.MdsCluster.recall(path)` — the MDS revoking a cap from a client when a conflicting access arrives.

### Reed-Solomon (RS)
EC family. `dfs-erasure.ReedSolomon` — XOR-stub implementation; see [ADR-0003](./decisions/0003-xor-parity-stub-not-galois.md).

### Replica
A copy of a chunk pinned to an OSD. `dfs-common.ReplicaId(ChunkId, OsdId)`.

### Replication
N-way replication. `dfs-erasure.Replication`. Storage cost N×, repair-read 1×.

### Reservation (dmClock tag)
The guaranteed minimum IOPS for a class.

## S

### Sandbox
Not used in this repo. The closest analog is the OJ project's microVM.

### Scrub
Background data-integrity check. Two variants in the wiki: shallow (metadata only) and deep (block content). Mapped to `PriorityClass.SHALLOW_SCRUB` / `DEEP_SCRUB`.

### Sealed extent
An extent in `ExtentStatus.SEALED`. No further appends accepted; `length` is the durable byte count.

### Shard (MDS)
A partition of the namespace owned by one MDS node. `dfs-mds.SubtreePartitioner.assign(subtree, mdsId)`.

### StrawSelector
The hashed-weight selection algorithm at the heart of CRUSH. `dfs-crush.StrawSelector`. Adding a bucket only steals from existing buckets in proportion to weight.

### Subtree partitioning
See **dynamic subtree partitioning**.

## T

### TenantId
`dfs-common.TenantId(String)` — tenant boundary identifier. Used by `dfs-security.Kms.generateDuk(tenant, obj)`.

### Topology
The hierarchy in the CRUSH map: root → row → rack → host → osd. Encoded in `dfs-crush.CrushBucket`.

### Tier transition
Background re-encoding from 3× replication to LRC, or LRC to deeper LRC. Modeled as `PriorityClass.TIER_TRANSITION`.

## V

### Virtual time (dmClock)
Per-class tag that advances by `1/rate` on each submission. The scheduler picks the class with the smallest tag.

## W

### WAL (Write-Ahead Log)
In `dfs-storage.Osd`, the in-memory queue that absorbs writes below the small-write threshold (default 4 KB). Background flushed into aligned extents via `flushDeferred`.

### Watchdog
Not implemented in this repo. The closest analog is the OJ project's per-VM watchdog.

### Weight (dmClock tag)
A class's proportional share of capacity once reservations are satisfied.

### WorkItem
`dfs-custodian.WorkItem(PgId, PriorityClass, reason)` — one unit of background work emitted by the scanner and dispatched by the Custodian.
