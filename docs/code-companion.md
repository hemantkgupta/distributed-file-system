# Distributed File System — Code Companion

> Last reconciled with the repo on 2026-05-20.

Maps every section of the standard blog ([`~/CSE-Raw/raw-blog/distributed-file-system.md`](../../CSE-Raw/raw-blog/distributed-file-system.md)) to the Gradle modules, source files, and tests that implement it. The blog argues *what* the architecture is; this companion shows *where* in the Java code each load-bearing claim lives — and, when a claim has no implementation, says so plainly.

## Sync rule

> **Wiki concept exists ⇒ a `docs/modules/<m>.md` page exists ⇒ matching code exists.**
> If any of those three is missing, the [Gaps](#gaps-blog-claims-the-code-does-not-implement) section names it. The blog should not advertise a mechanism that has no module page; a module page should not exist without a primary wiki anchor. This file is the bridge that keeps the three sides in agreement.

The other doc-tree page that enforces the rule from the wiki side is [`modules/README.md`](./modules/README.md)'s concept → module table. Read these two together.

---

## Blog Part → Code Map

### Part 1 — Disaggregate Control from Data, or Die

- **Wiki concept(s):** [`patterns/disaggregated-control-data-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/disaggregated-control-data-plane.md).
- **Module(s):** The whole repo is the demonstration — control-plane modules (`dfs-mds`, `dfs-monitor`, `dfs-custodian`, `dfs-qos`) sit in different Gradle subprojects from the data-plane modules (`dfs-node`, `dfs-storage`, `dfs-allocator`, `dfs-erasure`). The Gradle dependency graph in [`build.gradle`](../build.gradle) encodes the rule: no data-plane module depends on a control-plane module.
- **Key classes:** the dependency rules themselves — see ADR [`0004`](./decisions/0004-15-modules-by-concern.md). `dfs-node/.../NodeApi.java` is the only file that composes a data-plane write end-to-end.
- **Module page:** [`modules/dfs-node.md`](./modules/dfs-node.md) (data plane entry point); the control-plane pages collectively defend the other side.

### Part 2 — Capacity Math at 10 EB

- **Wiki concept(s):** none — pure architecture-math motivation. Drives sizing assumptions consumed elsewhere.
- **Module(s):** none. The numbers are absorbed into module pages (e.g. `dfs-mds` page cites the 2 KB-per-dentry figure).
- **Module page:** N/A. Architecture-only.

### Part 3 — Sharded Metadata Over a Transactional KV Store

- **Wiki concept(s):** [`patterns/sharded-metadata-over-kv-store`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/sharded-metadata-over-kv-store.md), [`tradeoffs/single-master-vs-sharded-metadata`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/single-master-vs-sharded-metadata.md).
- **Module(s):** `dfs-mds` (the namespace shard owner). The "transactional KV store" is stubbed to `ConcurrentHashMap<String, Inode>` — see ADR [`0002`](./decisions/0002-in-memory-substrates.md).
- **Key classes:** `dfs-mds/.../MdsCluster.java` (the in-memory inode table + cap table); `Inode.java`, `InodeType.java` (the durable state shape); `SubtreePartitioner.java` (shard-ownership routing — see Part 8).
- **Module page:** [`modules/dfs-mds.md`](./modules/dfs-mds.md).

### Part 4 — Hybrid Placement: CRUSH + Lookup

- **Wiki concept(s):** [`concepts/crush-placement-algorithm`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/crush-placement-algorithm.md), [`patterns/hybrid-deterministic-lookup-placement`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/hybrid-deterministic-lookup-placement.md), [`tradeoffs/crush-vs-lookup-placement`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/crush-vs-lookup-placement.md).
- **Module(s):** `dfs-crush` for the deterministic hop; `dfs-placement` for the PG lookup table; the two are composed by `dfs-node`.
- **Key classes:** `dfs-crush/.../Crush.java` (the placement function), `dfs-crush/.../StrawSelector.java` (straw2 weighted selection), `dfs-crush/.../CrushMap.java`; `dfs-placement/.../Placement.java` (object → PG hash), `dfs-placement/.../BlockLayer.java` (PG → OSDs lookup).
- **Module page:** [`modules/dfs-crush.md`](./modules/dfs-crush.md), [`modules/dfs-placement.md`](./modules/dfs-placement.md).

### Part 5 — Get the Kernel out of the Data Path (BlueStore)

- **Wiki concept(s):** [`concepts/bitmap-allocator`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/bitmap-allocator.md), [`tradeoffs/posix-fs-vs-raw-block-backend`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/posix-fs-vs-raw-block-backend.md).
- **Module(s):** `dfs-allocator` (the L0/L1 bitmap cascade), `dfs-storage` (the user-space OSD that the allocator serves).
- **Key classes:** `dfs-allocator/.../BitmapAllocator.java`, `Range.java`; `dfs-storage/.../Osd.java` (CRC32c, deferred WAL packing, CoW-style metadata transactions stubbed onto `ConcurrentSkipListMap`).
- **Module page:** [`modules/dfs-allocator.md`](./modules/dfs-allocator.md), [`modules/dfs-storage.md`](./modules/dfs-storage.md).

### Part 6 — Replication, LRC, ClayCodes

- **Wiki concept(s):** [`concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md), [`concepts/local-reconstruction-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/local-reconstruction-codes.md), [`concepts/clay-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/clay-codes.md), [`tradeoffs/rs-vs-lrc-erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/rs-vs-lrc-erasure-coding.md).
- **Module(s):** `dfs-erasure`.
- **Key classes:** `dfs-erasure/.../Replication.java` (3×); `dfs-erasure/.../ReedSolomon.java` (XOR parity *stub* — ADR [`0003`](./decisions/0003-xor-parity-stub-not-galois.md)); `dfs-erasure/.../LRC.java` (cost arithmetic only, no parity bytes).
- **Module page:** [`modules/dfs-erasure.md`](./modules/dfs-erasure.md).
- **Caveat:** ClayCodes have no class. True GF(2^8) RS has no class. See [Gaps](#gaps-blog-claims-the-code-does-not-implement).

### Part 7 — Consistency Without Consensus: Leases + Extent Sealing

- **Wiki concept(s):** [`concepts/chunk-lease`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/chunk-lease.md), [`concepts/extent-sealing`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/extent-sealing.md).
- **Module(s):** `dfs-lease`.
- **Key classes:** `dfs-lease/.../LeaseService.java` (chunk-lease grants); `dfs-lease/.../ExtentService.java` (`open` → `append` → `seal` lifecycle); `ChunkLease.java`, `Extent.java`, `ExtentStatus.java`.
- **Module page:** [`modules/dfs-lease.md`](./modules/dfs-lease.md), plus flow [`flows/extent-sealing.md`](./flows/extent-sealing.md).

### Part 8 — Capability Vectors and Dynamic Subtree Partitioning

- **Wiki concept(s):** [`concepts/capability-vector`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/capability-vector.md), [`concepts/dynamic-subtree-partitioning`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dynamic-subtree-partitioning.md).
- **Module(s):** `dfs-mds`.
- **Key classes:** `dfs-mds/.../CapabilityVector.java` (grant/recall semantics); `dfs-mds/.../MdsCluster.java` (per-path cap-holder table); `dfs-mds/.../SubtreePartitioner.java` (subtree → MDS routing with load-based rebalancing).
- **Module page:** [`modules/dfs-mds.md`](./modules/dfs-mds.md), plus flow [`flows/cap-recall.md`](./flows/cap-recall.md).

### Part 9 — Custodians and dmClock

- **Wiki concept(s):** [`patterns/custodian-background-control-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/custodian-background-control-plane.md), [`concepts/dmclock-qos`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dmclock-qos.md).
- **Module(s):** `dfs-custodian` (the scanner + dispatcher), `dfs-qos` (the dmClock scheduler), `dfs-monitor` (the cluster state the custodian reads).
- **Key classes:** `dfs-custodian/.../Custodian.java`, `dfs-custodian/.../RepairScanner.java`, `WorkItem.java`, `PriorityClass.java`; `dfs-qos/.../DmClockScheduler.java` (R/W/L tag scheduling), `QosClass.java`.
- **Module page:** [`modules/dfs-custodian.md`](./modules/dfs-custodian.md), [`modules/dfs-qos.md`](./modules/dfs-qos.md), [`modules/dfs-monitor.md`](./modules/dfs-monitor.md), plus flow [`flows/repair-on-disk-failure.md`](./flows/repair-on-disk-failure.md).

### Part 10 — The Failures That Get You

- **Wiki concept(s):** distributed across operational concept pages — no single anchor.
- **Module(s):** the failure modes are exercised by tests rather than dedicated classes. Map-propagation churn → `dfs-monitor` (`Monitor.bumpMapVersion`); cap-recall storms → `dfs-mds` (`CapabilityVector` recall path); near-full ENOSPC → `dfs-allocator` (allocator refuses past threshold); KV-store hiccups → in-memory substrates (so the symptom can't be reproduced — see [Gaps](#gaps-blog-claims-the-code-does-not-implement)).
- **Module page:** [`modules/dfs-monitor.md`](./modules/dfs-monitor.md), [`modules/dfs-mds.md`](./modules/dfs-mds.md), [`modules/dfs-allocator.md`](./modules/dfs-allocator.md).

### Part 11 — Three Levels of Depth

- **Wiki concept(s):** none — pedagogical structure.
- **Module(s):** the "At Scale" tier roughly maps onto the union of all 15 modules; the "Foundation" tier is `dfs-crush` + `dfs-placement` + `dfs-lease` + `dfs-node`.
- **Module page:** N/A.

### Part 12 — What Makes This Question Hard

- **Wiki concept(s):** the load-bearing decisions are individually covered above. The list is a summary, not a new mechanism.
- **Module(s):** crypto-shredding (decision #10) lands in `dfs-security` — `Kms.java` uses real AES-GCM (ADR [`0005`](./decisions/0005-aes-gcm-real-crypto.md)). Flow: [`flows/crypto-shred-erasure.md`](./flows/crypto-shred-erasure.md). The other decisions are pointers to earlier parts.
- **Module page:** [`modules/dfs-security.md`](./modules/dfs-security.md).

---

## Gaps (blog claims the code does not implement)

These are mechanisms the blog mentions where the matching module page either says "stub" in §7 or the concept has no module page at all. Read alongside each module's *Stubs and departures from production* section.

| Blog reference | Status in code | Where it shows up |
|---|---|---|
| **ClayCodes** (Part 6) | Not implemented. `dfs-erasure` ships `Replication`, `ReedSolomon` (stub), and `LRC` (cost-only). | [`modules/dfs-erasure.md`](./modules/dfs-erasure.md) §7. |
| **True Galois-field Reed-Solomon** (Part 6) | Replaced by an XOR-with-rotation stub. Output shape is RS-like; the decoder cannot reconstruct missing data shards from parities. | ADR [`0003`](./decisions/0003-xor-parity-stub-not-galois.md); `dfs-erasure/.../ReedSolomon.java`. |
| **Paxos-replicated monitor** (Part 10, implied by "cluster map versioning at scale") | `dfs-monitor` is single-node, in-process. No quorum, no log replication, no leader election. | [`modules/dfs-monitor.md`](./modules/dfs-monitor.md) §7. |
| **Production KMS with HSM-backed keys** (Part 12 crypto-shredding) | Real AES-GCM, but keys live in heap (`ConcurrentHashMap<DukId, SecretKey>`). A JVM core-dump leaks key material. | ADR [`0005`](./decisions/0005-aes-gcm-real-crypto.md) "Negative" section; `dfs-security/.../Kms.java`. |
| **End-to-end wired data path** (Part 1 / Part 5 / Part 7) | `dfs-node.NodeApi.put` composes CRUSH + Block Layer + LeaseService + ExtentService but does **not** persist bytes through `dfs-storage.Osd`. The extent log is in-memory inside `ExtentService`; the OSD is reachable but never receives the write. | [`modules/dfs-node.md`](./modules/dfs-node.md) §7; verifiable from `NodeApi.java` (no `Osd` import). |
| **RocksDB on raw block via BlueFS** (Part 5) | `Osd` uses `ConcurrentSkipListMap` for object data and a `HashMap` for CRCs. No LSM compaction, no WAL replay, no crash recovery. | ADR [`0002`](./decisions/0002-in-memory-substrates.md); `dfs-storage/.../Osd.java`. |
| **Transactional KV store under the MDS** (Part 3) | Same substitution — `ConcurrentHashMap<String, Inode>`. No transactions across shards. | ADR [`0002`](./decisions/0002-in-memory-substrates.md); `dfs-mds/.../MdsCluster.java`. |
| **Cross-shard `rename` atomicity** (Part 3) | Not modelled — single in-process MDS means there are no shard boundaries to cross. | [`modules/dfs-mds.md`](./modules/dfs-mds.md) §7. |
| **Network repair traffic / spine-switch saturation** (Part 6, Part 9) | Repair is in-process invocation. Bandwidth math from the blog isn't reproducible because nothing is on the wire. | [`modules/dfs-custodian.md`](./modules/dfs-custodian.md) §7. |
| **Map-propagation micro-bursts** (Part 10) | Map versioning exists (`Monitor.mapVersion`); the client refresh storm doesn't, because there are no remote clients. | [`modules/dfs-monitor.md`](./modules/dfs-monitor.md) §7. |
| **Active-active synchronous geo-replication** (Part 12, decision #11) | Out of scope. No multi-region story in any module. | N/A. |
| **Object / file / block gateway façades** (Part 12, decision #12) | `dfs-node.NodeApi` is the only API surface; there is no separate S3, POSIX, or block-protocol gateway. | [`modules/dfs-node.md`](./modules/dfs-node.md). |

Each row in this table is something a reader could legitimately expect from the blog and not find in the code. Module pages must keep their §7 honest about these so a careful reader who hits the gap finds the explanation immediately.
