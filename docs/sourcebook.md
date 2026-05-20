# Distributed File System (DFS) — LLM Sourcebook

This document is a comprehensive compilation of system architecture blueprints, theoretical specifications, codebase layouts, mapping documentation, and core Java reference implementations for a multi-module distributed file system (DFS). It is structured specifically to be consumed by LLM tooling (such as Google NotebookLM or Claude Projects) as a single sourcebook for querying, learning, and mapping codebase logic onto high-fidelity architectural concepts.

---

## Table of Contents
1. [Theoretical Blueprint: Distributed File System Essay](#1-theoretical-blueprint-distributed-file-system-essay)
2. [Repository Architecture & Dependency Model](#2-repository-architecture-and-dependency-model)
3. [Code Companion & Implementation Gaps](#3-code-companion-and-implementation-gaps)
4. [First 30 Minutes: Repository Getting Started](#4-first-30-minutes-repository-getting-started)
5. [Core Reference Implementation (Java Sources)](#5-core-reference-implementation-java-sources)
6. [System Glossary](#6-system-glossary)

---

## 1. Theoretical Blueprint: Distributed File System Essay

This section contains the complete theoretical specification of the distributed file system, outlining the disaggregated plane model, hybrid placement, raw-block user-space OSD mechanics, and tiered erasure coding algorithms.

### Introduction: The Architectural Commitment This Essay Defends

A cluster file system targeting one to ten exabytes of raw capacity, on the order of 10^11 files and 10^12 chunks, served across tens of thousands of commodity nodes, is a design problem where every textbook simplification fails. The GFS cartoon — single master holding the namespace in RAM, three-way replication of 64 MB chunks, POSIX storage on every chunkserver — falls over on three independent axes by the time the cluster is real. The master's RAM ceiling caps the namespace at hundreds of millions of files. Reed-Solomon's repair traffic saturates the spine switches the moment the cluster crosses a few thousand nodes. The local POSIX file system underneath each chunkserver burns five to ten times more write amplification than the cluster's own consistency layer needs. Solving any one of these without solving the others moves the bottleneck rather than removing it.

This essay defends a single architectural commitment with five concrete consequences. The commitment is *disaggregation of the control and data planes over a sharded transactional KV substrate, with the control plane itself decomposed into metadata, placement-lookup, and quorum-replicated cluster-map services that scale on independent bottlenecks.* The consequences are: (1) **hybrid placement** — hash to placement groups cheaply on the client; look up the PG's physical OSDs in a small KV store — so neither pure CRUSH map propagation nor pure per-object lookup wins or loses absolutely; (2) **raw-block user-space storage** — BlueStore-class daemons that bypass the kernel file system entirely, so crash consistency, free-space allocation, and write-amplification become the cluster's problems to optimise rather than the kernel's defaults to tolerate; (3) **tiered erasure coding by data temperature** — three-way replication for the hottest few percent, Local Reconstruction Codes for the warm bulk, ClayCodes or deep-LRC for the cold tail — driven by a background re-encoder under QoS; (4) **extent sealing plus chunk leases** rather than per-write consensus, so a network partition is resolved by sealing the open extent at its last universally-committed length and rolling forward into a freshly-allocated extent rather than by running Paxos on the tail; and (5) **a Custodian background control plane** under dmClock per-OSD QoS, so scrub, repair, rebalance, and tier transition are themselves architecture rather than scripts that operations runs from the side.

The essay walks the architecture service by service. Nine §-numbered services across the metadata, placement, monitor, storage, custodian, client, and gateway tiers; cross-cutting concerns for metrics, isolation, security, and observability; and a final section on the genuinely-contested questions where the public literature has not yet converged. Every service section follows the same shape: role, internal components, schema and API surface, life of a request, and the specific failure modes whose mitigations determine whether the system survives a year of correlated hardware churn. The throughline is that operational reality is architecture: every architectural decision is justified or condemned by how it behaves under continuous disk failure, network partition, slow-disk poisoning, and capacity smoothing.

---

### Workload Distribution and Derived Implications

Capacity planning at exabyte scale is unusual because the workload is bimodal across multiple independent dimensions. File-size distribution is bimodal between many small files and few enormous files; read/write skew is bimodal between analytics scans and sync writes; update frequency is bimodal between immutable archival and continuously-rewritten working datasets; and access temperature is bimodal between the few percent of active bytes and the long cold tail. The architecture must derive its chunk size, metadata layout, repair strategy, and EC choice from these distributions rather than from a headline average.

#### File-size distribution

| Bucket | Share of files | Share of bytes | Typical content |
|---|---|---|---|
| < 1 MB | ~60% | ~3% | Logs, configs, small datasets, code, JSON, document fragments |
| 1 MB – 64 MB | ~30% | ~12% | Office files, source archives, design files, images |
| 64 MB – 1 GB | ~8% | ~25% | Parquet shards, video segments, model checkpoints |
| > 1 GB | ~2% | ~60% | Backups, raw video, training datasets, VM images |

The classical GFS 64 MB chunk size is wrong for the head of the distribution and right for the tail. The fix is a tiered chunking policy: small files coalesce into shared extents (Haystack-style needle packing) to bound per-file metadata; large files split into 64–256 MB chunks aligned to the EC stripe geometry. The same chunk-size constant cannot serve both. The metadata-to-data ratio is the hidden cost: a 10 KB file with a 256-byte metadata record is 2.5% overhead, while a 1 GB file with the same record is rounding error. At 10^11 files dominated by the small-file head, packing matters more than perfecting the large-file path.

#### Read/write skew

Analytics workloads are dominated by sequential reads at hundreds of MB/s per consumer, with occasional bulk writes from upstream ETL. Sync workloads (developer trees, home directories, CI artefacts) are dominated by random small writes with a heavy read-after-write pattern. ML training workloads are dominated by epoch-scale sequential reads of the same dataset for hours, often across thousands of GPUs simultaneously — a fan-out pattern that the architecture must absorb without resharding the dataset every epoch. Treating these as one workload produces a cluster that disappoints all three; treating them as three workloads sharing one substrate with per-tenant QoS separators.

#### Update frequency

Roughly 5% of files are updated monthly, 0.5% daily, and a tiny fraction — collaborative documents, hot databases, log heads — are updated continuously. The update tail is what forces extent sealing into the design: it is the path on which network partitions actually meet writes-in-flight. The vast immutable head of the distribution is where ClayCodes and deep-LRC pay off: data that will never be rewritten can afford the CPU and complexity of high-overhead-reduction codes because the write path's tail latency is not at stake.

#### Capacity sizing

Numbers, not adjectives.

| Metric | Estimate | Implication |
|---|---|---|
| Raw capacity | 10 EB | ~500,000 HDDs at 20 TB each, or a mix with NVMe hot tier |
| File count | 10^11 | Sharded metadata mandatory; single MDS RAM ceiling reached at ~100M |
| Chunk count | 10^12 | Block-layer lookup DB keyed on PG, not chunk: ~10^10 PGs |
| Storage nodes | 10,000 | 50 disks per node typical; rack-aware failure domains |
| Aggregate read bandwidth | 10 TB/s | Spine switch sizing; repair budget must fit within headroom |
| Aggregate write bandwidth | 2 TB/s | Hot tier 3-replica = 6 TB/s on the wire |
| Daily disk failure rate | ~0.1% | 500 disk failures per day; continuous reconstruction is the norm |
| Single-disk rebuild time | ~13 min | 20 TB / (100 peer disks × 250 MB/s); cap is network not disk |
| MDS RAM per dentry+inode | ~2 KB | 10^11 entries = 200 TB total → 10–50 shards of 4–20 TB |
| Block layer DB size | ~1 TB | 10^10 PGs × ~100 bytes per row |

The repair-bandwidth budget is what sets the EC choice. At 0.1% disk-failure-per-day across 500,000 disks, 500 disks per day are under reconstruction; each reads roughly 10× its capacity over the network under RS(10,4); the spine notices. LRC and ClayCodes exist exactly so the spine does not notice. The MDS RAM footprint is what sets the sharding count: 200 TB does not fit in one box, so the metadata fleet is 10–50 shards by construction, and the entire cap, lock, and namespace-routing layer has to be designed around that fact. The block layer's 1 TB is the only metadata in the system that fits comfortably on one (replicated) host; the engineering effort is in keeping it that small as the cluster grows.

---

### The Foundational Commitment — Disaggregated Control and Data Planes

The defining architectural commitment is that the control plane and the data plane are different machines, different stacks, and different scaling stories. GFS's single master held the entire namespace in RAM; HDFS's NameNode did the same. They scaled brilliantly to a few hundred million files and then hit a wall. The wall was not "we ran out of disks." The wall was "the master ran out of memory and we ran out of patience for its garbage-collection pauses." Linear scaling on one axis (capacity) hides a non-linear scaling problem on another (namespace), and the day the namespace breaks is the day the entire cluster stops accepting new files.

The fix is structural: the metadata layer scales on its own bottleneck — QPS, CPU, in-memory working set — and the data layer scales on a different one — bandwidth, IOPS, raw capacity. Coupling them forces the smaller workload to limit the larger. Google Colossus disaggregated by putting the metadata in Curators backed by Bigtable, with Custodians handling the background work that GFS's master used to do inline. Meta Tectonic disaggregated further into a three-layer service — Name, File, and Block layers each independently shardable — backed by ZippyDB. CephFS MDS disaggregated by giving the MDS its own scale-out story over Ceph RADOS. All three answer the same question; the names differ; the architecture has converged.

What disaggregation actually delivers is independent failure domains and independent deploy cycles. The metadata service can roll forward a software version without restarting any OSD. The OSDs can be rebooted in a rolling-window pattern without losing any client's metadata cap. The block layer can resharded by adding new shards and migrating PG ranges without any movement of bytes on disk. A cluster that survives ten years survives because every component can evolve at its own pace under its own deployment discipline; a cluster that fails after three years fails because every change forced a cluster-wide outage and the team eventually stopped changing things.

The corollary is that the control plane is itself disaggregated. The metadata service (§1), the block layer (§2), and the cluster monitor (§3) are three distinct services with three distinct durability stories. The MDS sits on a sharded KV store and is mostly soft state in front of that. The block layer is a small replicated KV with the placement lookup map. The cluster monitor is a Paxos quorum holding the cluster map, the OSD up/down/in/out state, and the lease registry. Collapsing any two of these together is GFS's mistake recapitulated at a different scale; keeping them separate is what lets the system answer "where does this object live?" and "is this OSD alive?" and "who has the cap on this file?" with three independent latency budgets and three independent failure modes.

---

### The 2026 Industry-Standard Technology Stack

The components have very different scaling profiles. The MDS is a stateless or near-stateless control plane over a sharded transactional KV. The block layer is a small replicated KV. The OSD is a C++ daemon directly managing a raw block device. The cluster monitor is a Paxos quorum. The Custodian is a stateless scanner. Each maps cleanly to a canonical technology choice; isolating them into distinct services lets each match the right runtime.

| Layer | 2026 Standard Technology | Strategic Justification |
|---|---|---|
| **Metadata Service (MDS)** | C++ daemon (Ceph-style) or Java framework (Tectonic-style) on a sharded transactional KV | Stateless owners on durable KV; deploy/restart independently |
| **KV Backing for MDS** | Bigtable (Colossus), ZippyDB (Tectonic), Spanner (global txn), FoundationDB (open-source equivalent) | Use KV expertise; durability, replication, transactions are solved problems |
| **Block Layer** | Sharded RocksDB cluster or FoundationDB; ~1 TB total at 10^10 PGs | Small enough to keep entirely hot in RAM across the fleet |
| **Cluster Monitor** | Paxos/Raft over RocksDB; 5-node quorum | CP store for cluster map, lease grants, OSD state |
| **Storage Daemon (OSD)** | BlueStore-class C++ daemon over raw block device | Zero-overhead crash consistency; bitmap allocator; RocksDB onode store |
| **OSD Embedded KV** | RocksDB column families (O, B, C) over BlueFS | Onode/extent/collection metadata; LSM compaction suits append workload |
| **EC Library** | Intel ISA-L for RS / LRC; Ceph clay plugin for MSR codes | Hardware-accelerated encoding; mature production usage |
| **QoS Scheduler** | dmClock at the OSD level | Reservation / Weight / Limit per tenant per op class; durability-threshold elevation |
| **Gateway Proxies** | HAProxy / Envoy / nginx in front of S3 / NFS / SMB heads | Stateless gateway scaling; TLS termination; tenant routing |
| **Monitoring** | Prometheus + Grafana + OpenTelemetry | Per-OSD, per-PG, per-tenant time series; SLO dashboards |
| **Daemon Runtime** | C++17/20 for OSD/MDS daemons; Go/Java for control-plane services | Predictable memory behaviour at the data path; productivity at the control path |
| **Gateway Runtime** | Java 21 (virtual threads) or Go for S3/NFS heads | High-fanout I/O; protocol translation is CPU-cheap |
| **In-flight Encryption** | TLS 1.3 between gateways; msgr2 / cephx between daemons | Mutual auth; integrity-protected RPC |
| **At-rest Encryption** | dm-crypt at device level; per-tenant DUKs in KMS for crypto-shredding | Compromised disks yield no plaintext; GDPR erasure via key destruction |
| **Coordination** | The cluster monitor's Paxos itself; etcd for some auxiliary state | CP store for membership and lease grants |
| **Geo-Replication** | Async snapshot manifest shipping; per-tenant policy | RPO seconds-to-minutes; never blocks local writes on WAN |
| **Observability** | Prometheus + Grafana; per-PG state distribution; slow-op tracking; scrub error counters | Tier-aware dashboards; SRE alerting |

The stack's heterogeneity is deliberate. The OSD's C++ daemon writes microseconds-per-IO code. The MDS's Java framework writes milliseconds-per-RPC code. The gateway's Go server writes tens-of-milliseconds-per-request code. Forcing them all into one runtime would either burn a millisecond per IO at the OSD or fight garbage collection at the gateway. The contract between layers is what is universal; the implementation is per-layer.

---

### Service Catalog

The architecture decomposes into nine services across three groups:
- **Control plane (§§1–3):** Metadata Service (MDS), Block Layer (placement lookup), Cluster Monitor (Paxos cluster map authority).
- **Data plane (§4):** Storage Daemon (OSD, BlueStore-class).
- **Operations and access (§§5–9):** Custodian (background control plane), Client Library, Object Gateway, POSIX Gateway, Geo-Replication.

| § | Service | Primary substrate | Failure domain |
|---|---|---|---|
| 1 | Metadata Service (MDS) | Sharded KV (Bigtable / ZippyDB / FoundationDB) | Shard owner per subtree or hash range |
| 2 | Block Layer | Replicated RocksDB cluster | Region or cell |
| 3 | Cluster Monitor | Paxos quorum | Quorum across racks/AZs |
| 4 | Storage Daemon (OSD) | Raw block device | One physical disk |
| 5 | Custodian | Stateless workers + work queue | None — restartable |
| 6 | Client Library | In-process at the application | Per-process |
| 7 | Object Gateway | HTTP server farm | Per-region pool |
| 8 | POSIX Gateway | NFS/SMB server farm | Per-region pool |
| 9 | Geo-Replication | Stateless shippers + KV cursor | Per-namespace replication policy |

---

### §1. Metadata Service (MDS / Curator)

#### Role

The MDS is the authority for the file-system namespace: paths, inodes, dentries, file→chunk mappings, capability grants, and locks. It is the layer where GFS dies and Colossus, Tectonic, and CephFS live. The architecture commits to *sharded* metadata on top of a transactional KV store; the MDS owners are stateless policy and cache, and the KV holds the durable truth.

The MDS does four jobs simultaneously:
1. **Path resolution and namespace operations** (microseconds-to-milliseconds): `lookup`, `readdir`, `stat`, `rename`.
2. **Capability management** (milliseconds-to-seconds): grant, refresh, recall.
3. **Lock management** (microseconds, in-RAM): per-inode read/write locks for serialization.
4. **Journal flush and KV write-through** (milliseconds, batched): durable persistence of every state change.

#### Internal Components

The cap manager tracks which clients hold which delegations on which inodes. The lock manager serializes conflicting operations against the same inode. The cache is the working set — a few percent of the namespace held in RAM, evicted under LRU. The subtree balancer (in dynamic subtree partitioning deployments) measures per-subtree load and migrates authority to idle peers when a hotspot is detected. The journal writer batches state changes into KV transactions and acks the client only after the KV commits.

#### Schema / API Surface

The durable records on the KV:

```
INODE RECORD
  key:    inode:{tenant_id}:{inode_id}
  value:  {
    inode_id        UUID
    type            FILE | DIR | SYMLINK
    mode            u16 (POSIX permission bits)
    uid, gid        u32 each
    atime, mtime, ctime, btime  u64 (nanoseconds since epoch)
    size            u64
    nlink           u32 (hardlink count)
    layout          {chunk_size, stripe_count, ec_profile}
    head_chunks     [chunk_record_ref, ...]  // for small inline files
    xattrs          map<string, bytes>
    quota_class     u16
    region_policy   string
    encryption_duk  bytes  // wrapped per-inode key for crypto-shredding
    hold_count      u32
    tombstoned_at   timestamp | null
  }

DENTRY RECORD
  key:    dirent:{parent_inode_id}:{normalized_name}
  value:  {
    inode_id        UUID
    type            FILE | DIR | SYMLINK
    created_at      timestamp
  }

JOURNAL RECORD
  key:    journal:{shard_id}:{logical_clock}
  value:  {
    op              CREATE | UNLINK | RENAME | SETATTR | CAP_GRANT | CAP_RECALL | ...
    inode_id        UUID
    parent_inode_id UUID
    op_payload      bytes  // op-specific
    client_id       UUID
    cap_epoch       u64
  }

CAP RECORD
  key:    cap:{client_id}:{inode_id}
  value:  {
    cap_bits        u32 (pAsLsXsFr...)
    issued_at       timestamp
    refresh_due_at  timestamp
    epoch           u64
  }
```

The RPC surface the MDS exposes to the client library:

```
rpc lookup(parent_inode_id, name) returns (inode_record, cap_grant)
rpc open(inode_id, mode) returns (cap_grant, file_layout)
rpc readdir(inode_id, cursor) returns (dentry_batch, next_cursor)
rpc stat(inode_id) returns (inode_record)
rpc create(parent_inode_id, name, mode, layout_hint) returns (inode_record, cap_grant)
rpc unlink(parent_inode_id, name) returns (ok)
rpc rename(src_parent, src_name, dst_parent, dst_name) returns (ok)
rpc setattr(inode_id, attr_mask, attrs) returns (ok)
rpc cap_refresh(client_id, cap_list) returns (refreshed_caps)
rpc cap_release(client_id, inode_id, dirty_state) returns (ok)
rpc fsync(inode_id, cap_epoch) returns (ok)  // flushes journal through cap epoch
```

#### Life of a Request

##### `open("/var/log/app.log", O_RDWR)`:
1. Client library walks the path, looking up each component against its dentry cache. Hits short-circuit.
2. On miss, client RPCs `lookup(parent_inode_id, "var")` to the MDS shard owning that subtree.
3. MDS returns the inode_record and a path-element cap (read-only, short-TTL).
4. Client repeats for `log`, `app.log`. Each lookup may hit a different MDS shard if the subtree boundary crosses.
5. On the final lookup, client RPCs `open(inode_id, RW)` to the inode's MDS.
6. MDS acquires the exclusive write lock; checks for conflicting caps held by other clients; if a peer client holds a write cap, MDS issues a recall to that peer and waits for the flush ack.
7. MDS writes a journal record (CAP_GRANT); the journal commits to the KV.
8. MDS returns the cap_grant (`pAsXsFwb`) plus the file_layout — the chunk size, stripe count, and EC profile under which this inode's data is laid out.
9. Client library hands the application a file descriptor backed by a local write buffer and a cluster-map cache.

##### `stat()`:
1. Client library checks if it already holds a fresh `A` cap on the inode. If yes, returns from cache. No RPC.
2. If no, client RPCs `stat(inode_id)` to the inode's MDS.
3. MDS returns the inode_record and issues an `A` cap for future cache hits.

##### `readdir`:
1. Client RPCs `readdir(inode_id, cursor=0, limit=1000)` to the directory's MDS.
2. MDS issues a range scan over `dirent:{inode_id}:*` keys against the KV.
3. Returns the dentry batch and a next_cursor.
4. Client iterates, issuing follow-up RPCs until next_cursor is empty.

##### `rename(src, dst)` within one MDS shard:
1. Client RPCs the MDS.
2. MDS acquires the lexicographically-smaller-first ordering of write locks on (src_parent, dst_parent, src_inode).
3. MDS writes a journal record (RENAME) that atomically updates the dentry rows.
4. Journal commits; locks release; ack to client.

#### Failure Modes

- **Cap-recall hang.** A misbehaving client holds a write cap and fails to flush dirty buffers when recalled. Mitigation: the MDS enforces a recall deadline (typically 60 seconds), then force-evicts the laggard, marks its dirty state as lost, and grants the cap to the peer. The operator gets an alert; the laggard's writes between its last fsync and the eviction are gone.
- **Subtree thrash.** Two peer MDS shards repeatedly migrate the same subtree back and forth as observed load oscillates. Mitigation: subtree migration is rate-limited (no more than once per hour per subtree by default) and uses an EWMA on the load signal rather than instantaneous load.
- **Cross-shard rename pain.** A workload that renames heavily across shard boundaries pays distributed-transaction latency on every rename. Mitigation: subtree migration to co-locate the source and destination subtrees; if the workload is structural, the deployment chooses range or hash sharding to keep related paths together.
- **MDS crash recovery.** An MDS shard owner crashes. Trigger: process kill, OOM, hardware failure. Symptom: clients whose caps are owned by that shard see RPC timeouts. Mitigation: the cluster monitor (§3) detects the failure via heartbeat, elects a peer owner, and the new owner replays the journal from the KV. Recovery is bounded by the journal tail length (typically seconds). Clients reconnect, re-acquire caps, and continue.
- **Cap-recall storms.** A flash crowd opening the same hot file in turn invalidates each prior holder's exclusive cap. The MDS sits there waiting for flushes. Mitigation: rate-limit the recall path; degrade hot files to shared-cap mode automatically; defer conflict resolution under load.
- **KV-store latency spikes.** The MDS's underlying KV experiences a hiccup. Trigger: KV compaction storm, rolling restart, network partition within the KV. Symptom: every MDS RPC slows. Mitigation: aggressive client-side caching via caps means most operations don't hit the KV; for the operations that do, the MDS returns a degraded-mode error and the client retries with backoff.

---

### §2. Block Layer (Placement Lookup Tier)

#### Role

The block layer answers the question "for placement group X, which physical OSDs hold the replicas?" It is the right half of the hybrid placement decision: the client hashes its object to a PG cheaply, then looks up the PG's current physical OSDs in this layer. The block layer is a small, replicated KV — typically ~1 TB at 10^10 PGs — that is small enough to keep entirely hot across a few replicated nodes.

The pure-CRUSH design eliminates this layer entirely: the cluster map is what the client computes against. The pure-lookup design keeps every chunk's placement in the KV. The hybrid splits the difference. The PG is the unit at which placement is decided; the chunk inherits its PG's placement. PGs are coarse enough to keep the DB small and fine enough to spread load evenly.

#### Internal Components

The block layer's KV is replicated across a small quorum (3–5 nodes per region) for HA. Reads are served from any replica; writes go through a leader. The hot working set is small enough that almost every read hits the in-process cache.

#### Schema / API Surface

```
PG RECORD
  key:    pg:{pool_id}:{pg_id}
  value:  {
    pool_id          u16
    pg_id            u32
    generation       u64                // monotonically increasing; bumped on every change
    primary_osd      u32
    secondary_osds   [u32, ...]         // replica or EC peers
    ec_profile       u8                 // 0 = 3-replica, 1 = RS(6,3), 2 = LRC(12,2,2), ...
    state            ACTIVE | RECOVERING | DEGRADED | INCONSISTENT | DOWN | PEERING
    last_clean       timestamp
    bytes_used       u64
    bytes_capacity   u64
  }
```

The RPC surface:

```
rpc get_pg(pool_id, pg_id) returns (pg_record)
rpc list_pgs(pool_id, cursor) returns (pg_batch, next_cursor)
rpc update_pg(pg_id, expected_generation, new_state) returns (new_generation | conflict)
rpc split_pg(pg_id, expected_generation) returns ([new_pg_a, new_pg_b], new_generation)
rpc merge_pg(pg_id_a, pg_id_b, expected_generation) returns (merged_pg, new_generation)
```

Every read returns the generation alongside the placement. Every write is a compare-and-swap on (pg_id, expected_generation). This is the substrate that prevents a stale client from writing into a now-rebalanced PG.

#### Life of a Request

##### Placement decision flow (write):
1. Client wants to write object `O` with content hash `H`.
2. Client computes `pg_id = hash(H) mod pg_count_for_pool`.
3. Client checks its in-process PG cache for `(pool_id, pg_id)`. Cache hit: skip to step 5.
4. Cache miss: client RPCs `get_pg(pool_id, pg_id)` to the block layer. Block layer returns the pg_record.
5. Client opens an RPC to the primary OSD, tagging the request with the generation it knows.
6. Primary OSD checks the generation against its local view. Match: proceed with the write. Mismatch: return a redirect indicating the latest generation; client re-fetches from the block layer.

##### PG split / merge:
The Custodian (§5) detects an imbalance — a PG that has grown too large to balance load, or two PGs that are both too small to justify their metadata footprint. It computes the new layout, then runs a multi-step protocol: pause writes on the affected PG via cluster-monitor lease, copy data into the new PG layout on the OSDs, update the block layer atomically (via the CAS-on-generation API), then resume writes.

#### Failure Modes

- **KV outage.** The block layer's KV is unavailable. Mitigation: client cache is generous (5% of PGs covers >50% of hot traffic); for the rest, clients return a transient error to the application and retry with backoff.
- **Generation skew.** Client cached generation is far behind the cluster's current generation. Mitigation: clients aggressively refresh on the first redirect for any PG; the block layer responds with a batch of recent PG records so the client can refresh many at once.
- **PG split/merge during write.** A client mid-write encounters a PG split. Mitigation: the multi-step protocol pauses writes via cluster-monitor lease; the OSD returns a retry-after error indicating the brief unavailability; the client retries and finds the new PG.
- **Block layer leader failure.** The KV's leader fails. Mitigation: Raft/Paxos election; reads continue against followers.

---

### §3. Cluster Monitor (Quorum-Replicated State)

#### Role

The cluster monitor is the Paxos quorum that holds the cluster map, the OSD up/down/in/out state, the lease registry, and the durability watcher. It is the source of truth for "what is the current physical topology of this cluster," and it is the issuer of leases that the block layer and MDS rely on for serialization.

The monitor is the smallest service by data volume — kilobytes to megabytes of state — but the most consequential by impact. A monitor outage means no new leases, no map propagation, no failure handling. The cluster's data plane continues to operate against the most recent cluster map for some bounded grace period; if the monitor stays down past that, the cluster goes read-only.

#### Internal Components

The Paxos state machine replicates every state change across the 5-node quorum. The cluster map encodes the OSD topology — racks, hosts, disks, weights, failure domains. The lease registry encodes who holds which leases on what. The OSD state tracks the up/down/in/out state of every OSD.

#### Schema / API Surface

```
CLUSTER MAP RECORD
  key:    clustermap:current
  value:  {
    epoch              u64
    osd_count          u32
    osds               [{osd_id, host, rack, dc, weight, state}]
    pool_definitions   [{pool_id, ec_profile, replication_factor, pg_count}]
    crush_rules        bytes  // for clients still doing local placement
    updated_at         timestamp
  }

LEASE RECORD
  key:    lease:{type}:{resource_id}
  value:  {
    type             CHUNK | SUBTREE | PG_SPLIT
    resource_id      bytes
    holder           u32  // OSD or MDS id
    epoch            u64
    granted_at       timestamp
    expires_at       timestamp
    fence_token      u64
  }

OSD HEARTBEAT
  {
    osd_id             u32
    epoch              u64
    last_seen          timestamp
    bytes_used         u64
    bytes_capacity     u64
    slow_op_count      u32
    pg_state_summary   {active_clean: u32, recovering: u32, ...}
  }
```

The RPC surface:

```
rpc get_clustermap(min_epoch) returns (clustermap)
rpc subscribe_clustermap() returns (stream<clustermap>)
rpc grant_lease(type, resource_id, holder, duration) returns (lease_record)
rpc renew_lease(lease_id, fence_token) returns (renewed_lease)
rpc revoke_lease(lease_id, expected_epoch) returns (ok)
rpc heartbeat(osd_id, epoch, ...) returns (ack)
rpc mark_osd_down(osd_id, reason) returns (new_clustermap_epoch)
rpc mark_osd_in(osd_id) returns (new_clustermap_epoch)
```

#### Life of a Request

##### Lease grant (chunk write):
1. Primary OSD for a newly-allocated chunk wants the chunk lease.
2. OSD RPCs `grant_lease(CHUNK, chunk_id, primary_osd, 60s)` to the monitor.
3. Monitor's Paxos state machine validates: no existing lease on this chunk; OSD is up and in; capacity available.
4. Monitor writes the lease record via Paxos and returns the lease with a fence_token.
5. Primary OSD now drives writes to the chunk. Secondary OSDs accept writes from this primary only if the fence_token matches.
6. Lease expires; primary RPCs `renew_lease` before expiry or the lease lapses.

##### Lease revoke (extent sealing path):
1. Primary OSD's heartbeat times out at the monitor's failure detector.
2. Monitor marks the OSD `down` in the cluster map and revokes all leases held by it.
3. Monitor publishes the new cluster map epoch to subscribers (block layer, MDS, OSD peers).
4. The chunk's secondary OSDs receive notice that the lease is revoked; they seal the open extent at its last universally-committed length.
5. Cluster monitor grants a new lease for a freshly-allocated chunk to a new primary.

##### Map propagation:
Every subscriber holds a long-poll RPC against `subscribe_clustermap`. When the monitor's Paxos commits a new map, the long-polls return with the new epoch. Subscribers fetch the diff.

#### Failure Modes

- **MON quorum loss.** Three of five monitor nodes are unreachable simultaneously. Mitigation: clients and data-plane services continue against the last-known map for a grace period (typically minutes); after grace, the cluster goes read-only.
- **Map version churn.** Continuous topology change pushes a new map every few seconds. Mitigation: map updates are batched (no more than once per second per change category); diffs rather than full maps are pushed; subscribers cache aggressively.
- **Heartbeat storms.** Every OSD reports state every second across 500,000 OSDs. Mitigation: heartbeat aggregation through intermediate aggregators (per-rack heartbeat collectors); the monitor sees one heartbeat per rack per interval rather than per disk.
- **Lease registry bloat.** Long-lived workloads accumulate millions of leases. Mitigation: lease compaction; clients hold one lease per inode rather than per chunk for hot files; lease records expire and re-grant lazily.

---

### §4. Storage Daemon (OSD, BlueStore-class)

#### Role

The OSD is the daemon that owns one physical disk. It accepts reads and writes against the chunks placed in the PGs assigned to it; it participates in EC encode/decode; it scrubs its own disk for bit-rot; it executes repair work the Custodian sends it; it schedules its own I/O under dmClock. The architecture commits to *BlueStore-class* OSDs: each daemon manages its disk in user space, reads and writes raw block extents, tracks free space via an in-RAM bitmap allocator, and holds object metadata in RocksDB. The kernel file system is not in the data path.

#### Internal Components

The bitmap allocator tracks free space in an in-RAM L0/L1/L2 cascade — 35 MB per TB of disk, O(1) amortised allocation regardless of fragmentation. It loads at boot from a persisted bitmap; updates are journaled to RocksDB and applied to the in-RAM structure.

RocksDB holds three column families: `O` for onodes (object metadata — name, size, extent list, checksums), `B` for block allocation metadata (the durable side of the bitmap), and `C` for collection logic (per-PG collections, omap data). BlueFS is a minimal user-space "filesystem" — really just a small file abstraction — that exists only to give RocksDB the small-number-of-files API it needs. BlueFS itself is implemented over the raw block device's extents.

The dmClock scheduler is per-OSD. It owns three numbers per tenant-or-op-class queue: Reservation (R), Weight (W), Limit (L). It dispatches I/O against these in virtual time. Background work (scrub, recovery) has its own queues; foreground client I/O has tenant queues. The durability watcher in the cluster monitor (§3) can elevate the R of repair work when a PG falls below its durability threshold, temporarily throttling clients.

#### Schema / API Surface

The on-disk layout:

```
Disk: [Superblock | BlueFS extents | RocksDB extents | Raw data extents]

OBJECT RECORD (onode, in RocksDB CF=O)
  key:    O:{collection_id}:{object_id}
  value:  {
    object_id        bytes
    size             u64
    extents          [{extent_id, logical_offset, physical_offset, length}]
    checksums        [{offset, crc32c}]  // per 4 KB block
    omap_root        bytes        // optional per-object KV
    xattrs           map<string, bytes>
    flags            u32 (sealed, ec-encoded, ...)
    epoch            u64
  }

EXTENT RECORD (in RocksDB CF=B)
  key:    B:{physical_offset}
  value:  {
    physical_offset  u64
    length           u32
    state            ALLOCATED | FREE | RESERVED_FOR_DEFERRED
  }

COLLECTION RECORD (in RocksDB CF=C)
  key:    C:{pg_id}
  value:  {
    pg_id            u32
    object_count     u64
    bytes_used       u64
    last_scrubbed_at timestamp
    last_deep_scrubbed_at timestamp
  }
```

The OSD RPC surface (towards primary, secondary, client):

```
rpc write(object_id, offset, bytes, fence_token, generation) returns (commit_lsn | redirect)
rpc read(object_id, offset, length, generation) returns (bytes | redirect)
rpc seal_extent(object_id, extent_id, sealed_length, fence_token) returns (ok)
rpc recover_object(object_id, source_osd_list) returns (ok)
rpc scrub_pg(pg_id, deep: bool) returns (scrub_result)
rpc heartbeat() returns (osd_state)
```

#### Life of a Request

##### Write path, large write (≥ min_alloc_size, typically 4 KB SSD / 64 KB HDD):
1. Client writes to the primary OSD via `write(object_id, offset, bytes, fence_token, generation)`.
2. Primary OSD's RPC handler enqueues the op on the dmClock scheduler with the client's tenant tag.
3. Scheduler dispatches the op. Primary asks the bitmap allocator for a fresh extent of `length(bytes)`.
4. Primary writes the payload to the raw block device at the allocated extent. Awaits the hardware flush.
5. Primary updates the onode in RocksDB: appends the new extent to the extent list; records per-4-KB CRC32c checksums.
6. Primary forwards the write to each secondary OSD via the cluster network, including the fence_token.
7. Each secondary repeats steps 3–5 locally.
8. Each secondary acks to the primary.
9. After all secondaries ack, the primary commits the metadata transaction in RocksDB; the commit_lsn advances.
10. Primary acks to the client.

##### Write path, small write (< min_alloc_size):
1. Client writes a 1 KB payload to the primary.
2. Primary recognizes the write is sub-min-alloc. Rather than waste a full block, it writes the payload + a small metadata record into the RocksDB WAL synchronously.
3. WAL commits; primary acks to the client.
4. A background "deferred write" thread later asynchronously packs the payload into a properly-aligned extent, updates the onode, and reclaims the WAL space.

##### Read path:
1. Client reads via `read(object_id, offset, length, generation)`.
2. OSD's RPC handler enqueues the op on the dmClock scheduler.
3. Scheduler dispatches; OSD looks up the onode in RocksDB.
4. OSD locates the extent(s) covering the requested range; reads bytes from the raw block device.
5. OSD verifies the CRC32c checksums recorded in the onode against the on-disk bytes.
6. Checksum match: OSD returns bytes. Checksum mismatch: OSD halts the read, marks the chunk inconsistent, raises a repair event, and returns a temporary error to the client. Client retries against a secondary or peer EC fragment.

##### Repair path:
1. Custodian (§5) detects a PG is degraded (a peer OSD is `out`); dispatches a repair work item.
2. Surviving OSDs receive `recover_object(object_id, source_osd_list)`.
3. Each surviving OSD reads its local chunks for the affected objects.
4. EC reconstruction (for warm/cold) or direct copy (for hot 3-replica) regenerates the missing chunks on the new destination OSDs.
5. Block layer (§2) is updated atomically (compare-and-swap on generation); cluster monitor publishes the new map.
6. dmClock keeps repair within its budget; durability-threshold elevation kicks in if a PG drops below its replica floor.

##### Scrub path:
1. Custodian or per-OSD scheduler initiates a shallow scrub: read every onode's metadata; verify size, extent count, and checksum invariants. Shallow scrubs run daily per PG.
2. Deep scrub: read every byte of every object; recompute CRC32c per 4 KB block; compare to the recorded checksum. Deep scrubs run weekly to bi-weekly per PG; suspended if client I/O spikes.
3. Mismatch found: chunk marked inconsistent; Custodian dispatches a repair from a clean peer.

#### Failure Modes

- **Slow disk.** A single disk's p99 spikes silently — no errors, just slower-than-peers. Mitigation: per-OSD slow-op counters compared against fleet-wide percentiles; an OSD that's an outlier for > N minutes is marked `out` automatically and its PGs migrate.
- **Near-full.** OSD crosses 85% full. Mitigation: Custodian initiates capacity smoothing under dmClock; at 90%, writes to this OSD are rejected to prevent ENOSPC corruption.
- **RocksDB compaction stall.** RocksDB's background compaction falls behind ingest. Trigger: a sustained metadata-write burst. Symptom: write latency climbs; eventually writes block. Mitigation: rate-limit metadata writes via dmClock; RocksDB compaction tuned for the workload; WAL on faster media than data extents.
- **Journal exhaustion.** RocksDB WAL grows faster than it can be checkpointed. Mitigation: throttle deferred writes via dmClock; provision more NVMe for WAL; tune the deferred-write batch size.
- **Partial-write replay.** OSD crashes mid-write; on restart, RocksDB replays the WAL. Mitigation: the cluster monitor recognizes the OSD as `down` during the crash and any in-flight writes are sealed under the extent-sealing protocol; the crashed OSD on recovery reconciles against the sealed state and discards anything past the seal point.
- **Checksum mismatch on read.** Silent bit-rot. Mitigation: chunk marked inconsistent; Custodian dispatches repair from a clean peer; cadence of deep scrub is the cadence at which silent corruption can hide.

The EC choice belongs at the OSD level only because the OSD owns the encode/decode CPU and the disk-level I/O. The cluster-wide policy ("hot tier = 3-replica; warm = LRC(12,2,2); cold = LRC(12,4,2) or ClayCodes") is set by the EC & Tiering policy under the Custodian (§5).

The two headline LRC configurations:

| Code | Storage overhead | Single-fragment reconstruction reads | Survives any |
|---|---|---|---|
| RS(6,3) | 1.5× | 6 | 3 fragments |
| LRC(12,2,2) | **1.33×** | 6 (matches RS(6,3)) | most 4-fragment patterns |
| LRC(12,4,2) | 1.5× (matches RS(6,3)) | **3 — half of RS(6,3)** | most 4-fragment patterns |

LRC(12,2,2) at 1.33× overhead matches RS(6,3) on single-fragment repair while saving 11.3% storage. LRC(12,4,2) at 1.5× matches RS(6,3) on storage and halves single-fragment repair. At exabyte scale, halving repair traffic during continuous reconstruction is the difference between a healthy cluster and one constantly chasing its tail. ClayCodes are MSR-optimal — they achieve the minimum possible repair bandwidth — at the cost of more involved CPU decoding via sub-packetization.

---

### §5. Custodian Control Plane

#### Role

The Custodian is the stateless background control plane that owns scrub, repair, rebalance, and tier transition. It is what Colossus separated out from the old GFS master: the recognition that operational primitives cannot be afterthoughts coupled to the foreground control path. The Custodian scans cluster state continuously, generates work items, dispatches them to OSDs under dmClock-throttled QoS, and tracks completion.

The defining property is that the Custodian's work is the cluster's. Coupling background work to the foreground control plane means every failure storm is a foreground latency event. Disaggregating it means a wave of correlated disk failures triggers a wave of repair work that the Custodian throttles globally, and the foreground control plane sees only the reduced dmClock weights — not the work itself.

#### Internal Components

The work scanner walks the cluster map and the block layer's PG records, looking for state that needs work: PGs that are degraded, PGs whose last_clean is older than the scrub interval, OSDs over 85% full, packs eligible for tier transition by heat.

The priority queue is ordered by *durability impact*: a PG with one surviving replica out of three has higher priority than a PG at 85% full.

The dispatcher pulls from the queue and sends RPCs to OSDs. The throttle controller measures cluster-wide repair traffic and slows dispatch if it crosses budget. The completion tracker records what's done, retries failures, and surfaces unsuccessful items to operators.

#### Schema / API Surface

```
WORK ITEM RECORD
  key:    work:{priority_class}:{logical_clock}:{work_id}
  value:  {
    work_id          UUID
    type             SCRUB | DEEP_SCRUB | REPAIR | REBALANCE | TIER_TRANSITION
    target_pg        u32
    target_osds      [u32, ...]
    source_osds      [u32, ...]  // for repair
    priority_class   u8 (0 = critical-durability, 1 = high, 2 = normal, 3 = low)
    bytes_estimate   u64
    enqueued_at      timestamp
    started_at       timestamp | null
    completed_at     timestamp | null
    retry_count      u8
  }

PRIORITY CLASSES
  0  CRITICAL_DURABILITY  // PG below replica floor; dmClock R elevated
  1  HIGH                 // PG degraded but above floor
  2  NORMAL               // routine scrub, tier transition
  3  LOW                  // capacity smoothing, batch re-encoding

THROTTLE ENVELOPE
  {
    cluster_wide_repair_bandwidth_max    bytes/sec
    per_osd_repair_bandwidth_max         bytes/sec
    per_osd_scrub_iops_max               iops
    cluster_load_signal                  ewma(client_latency_p99)
  }
```

The RPC surface:

```
rpc enqueue_work(work_item) returns (work_id)
rpc dispatch_work(osd_id, budget) returns (work_batch)
rpc complete_work(work_id, result) returns (ok)
rpc get_throttle_envelope() returns (envelope)
rpc update_throttle_envelope(envelope) returns (ok)  // operator-facing
```

#### Life of a Request

##### Single-disk repair:
1. OSD heartbeat times out at the cluster monitor; monitor marks OSD `out`.
2. Custodian's work scanner sees that every PG previously hosted on this OSD is now `degraded`.
3. Scanner enqueues a repair work item per affected PG, priority class HIGH (or CRITICAL_DURABILITY if the PG dropped below its replica floor).
4. Dispatcher pulls work items from the queue under the cluster-wide repair bandwidth budget. Per work item, it identifies surviving source OSDs and idle destination OSDs.
5. Dispatcher RPCs `recover_object` to each destination OSD with the source list.
6. Destination OSDs pull bytes from sources; for hot tier they pull a direct copy; for warm/cold they pull surviving EC fragments and reconstruct locally.
7. Block layer (§2) is updated atomically — new generation, new replica set, new state ACTIVE_CLEAN.
8. Completion tracker records the work item as complete.

##### Tier transition:
1. Heat tracker observes that a pack's read+write+age signal has dropped below `H_warm`.
2. Heat tracker emits a tier-transition work item, priority class NORMAL.
3. Dispatcher schedules the work during low-load windows.
4. OSD reads the hot 3-replica pack; encodes into LRC(12,2,2); writes the EC stripe across the warm pool.
5. After validation, the hot replicas are released.
6. Block layer is updated atomically; ec_profile changes from 0 to 2.

##### Capacity smoothing:
1. Work scanner detects that OSD X is 87% full while OSD Y in the same failure domain is 60% full.
2. Scanner emits rebalance work items targeting PGs on X, moving them to Y.
3. Dispatcher throttles aggressively (priority class LOW) to avoid impacting client I/O.

#### Failure Modes

- **Custodian fall-behind under correlated failure.** A rack failure produces a wave of work items; the Custodian can't dispatch fast enough. Mitigation: priority class CRITICAL_DURABILITY automatically elevates dmClock R for those PGs' repair; the rest queue and drain over time.
- **Priority inversion.** A low-priority rebalance work item holds a lock that a high-priority repair needs. Mitigation: work items are independent (no shared locks); the dispatcher's per-PG concurrency limit ensures only one work item per PG at a time.
- **Rebalance loops.** Capacity smoothing moves data from X to Y; later, X drops below Y due to writes; the algorithm moves data back. Mitigation: hysteresis bands; rebalance targets are computed against a fleet-wide median rather than pairwise; rebalance is rate-limited globally.
- **Throttle envelope mis-tuning.** Repair budget is too generous; client latency spikes. Or too conservative; PGs stay degraded forever. Mitigation: the throttle envelope is driven by an EWMA of client latency p99; if p99 climbs, repair bandwidth is automatically reduced; if PGs stay degraded for > T_alert, repair bandwidth is automatically increased.

---

### §6. Client Library (Thick Client)

#### Role

The client library is the in-process layer that translates application I/O into MDS RPCs, block layer lookups, and direct OSD writes. The architecture commits to *thick clients*: the client holds caps, caches cluster maps, buffers writes, and routes data directly to OSDs without traversing a gateway. The alternative — thin clients over a proxy — adds context switches and serialization overhead that bottleneck modern NVMe arrays. Thick clients are operationally harder to upgrade across a fleet of tens of thousands of consumer hosts, but the performance is non-negotiable on the data path.

The thick-client model is what makes capability vectors work: the cap is the contract that says "you may safely cache this." The library uses the cap to avoid synchronous MDS RPCs on the common path.

#### Internal Components

The cap cache holds the inode-id → cap-bits records the MDS has granted. The cluster map cache holds the current epoch and the PGs the library has resolved recently. The write buffer batches contiguous writes against an open file, holding dirty bytes until either the buffer fills, the application calls `fsync()`, the MDS recalls the write cap, or a flush timer fires. The page cache holds recently-read bytes. The scatter-gather coordinator splits a logical write into the chunks that span PGs and dispatches each to its primary OSD in parallel.

#### Schema / API Surface

The library exposes the POSIX surface to applications:

```c
int open(const char *pathname, int flags, ...);
ssize_t read(int fd, void *buf, size_t count);
ssize_t write(int fd, const void *buf, size_t count);
int close(int fd);
int fsync(int fd);
int stat(const char *pathname, struct stat *statbuf);
int rename(const char *oldpath, const char *newpath);
int mkdir(const char *pathname, mode_t mode);
DIR *opendir(const char *name);
struct dirent *readdir(DIR *dirp);
```

Plus an extended interface for S3-style and tenant-aware operations:

```c
int s3_put_object(const char *bucket, const char *key, const void *bytes, size_t len);
int s3_get_object(const char *bucket, const char *key, void *buf, size_t len);
int s3_multipart_init(const char *bucket, const char *key, char **upload_id);
int s3_multipart_put_part(const char *upload_id, int part_number, const void *bytes, size_t len);
int s3_multipart_complete(const char *upload_id);
```

#### Life of a Request

##### POSIX `open` + `read`:
1. Application calls `open("/data/file.bin", O_RDONLY)`.
2. Library walks the path against the cap cache. Each path element may hit (cached dentry + lookup cap) or miss.
3. On miss, library RPCs MDS for the missing element; receives inode_record + lookup cap.
4. Final element: library RPCs MDS `open` for `inode_id` with `O_RDONLY`; receives read cap + file_layout.
5. Library returns an fd to the application; no bytes have been read yet.
6. Application calls `read(fd, buf, 1048576)` — request for 1 MB at offset 0.
7. Library checks page cache. If hit, return. If miss, library identifies the chunk(s) covering the range from file_layout.
8. For each chunk, library hashes to PG; consults map cache for the PG's OSD list.
9. Library RPCs each OSD with the chunk read in parallel; awaits responses.
10. OSDs return bytes; library verifies its own checksums against the file_layout; assembles into the user buffer; updates page cache.
11. Library returns to application.

##### POSIX `write` + `fsync`:
1. Application calls `write(fd, buf, 65536)`.
2. Library appends to the write buffer for `fd`; returns immediately.
3. When buffer fills or `fsync` is called, library flushes: for each chunk covered, hash to PG, look up OSDs, RPC the primary with the write payload and the current cap epoch.
4. Primary OSD writes to its disk and forwards to secondaries (§4 write path).
5. Primary acks to library; library acks the `fsync` to application.
6. On `close`, library does an implicit `fsync` if dirty; releases the cap with `cap_release` to the MDS.

#### Failure Modes

- **Stale cluster map.** Library's map cache is far behind; OSD returns redirect. Mitigation: aggressive refresh on first redirect; subscribe to map updates for hot PGs.
- **Cap revocation deadline.** MDS recalls a write cap; library must flush dirty buffer within the deadline. Mitigation: library prioritizes flush-on-recall over normal-priority writes; if the flush cannot complete in time, library returns an error to the application and accepts the cap revocation.
- **Write buffer overflow.** Application writes faster than library can flush to OSDs. Mitigation: library exerts backpressure on the application; configurable buffer size.
- **Retry storms.** A wave of stale maps causes every client to refetch simultaneously. Mitigation: jittered backoff; subscribers prefer diffs over full maps; the monitor's subscribe channel coalesces.
- **MDS owner change mid-operation.** Library is talking to MDS shard A; shard A's authority migrates to shard B mid-operation. Mitigation: library follows the redirect transparently; the migrating shards run a brief two-phase commit.

---

### §7. Object Gateway (S3 REST / Swift / RGW-class)

#### Role

The object gateway exposes the cluster via the S3 REST protocol (and Swift, where deployed). It is a stateless HTTP server farm that translates S3 verbs (PUT, GET, DELETE, multipart) into client-library calls against the underlying file system.

The gateway is stateless: every request is a fresh client-library session. Auth and IAM logic, bucket-to-namespace mapping, multipart-upload coordination, and listing index maintenance are the gateway's responsibilities; durability of the bytes is the underlying file system's.

#### Internal Components

The load balancer forwards requests to the Gateway servers. The auth module verifies IAM signatures. The gateway coordinates with a KMS for key management. It translates incoming S3 paths via bucket mapping, handles multipart uploads, and indexes lists for S3 buckets before making calls to the Client Library.

#### Schema / API Surface

The S3 REST verbs the gateway supports:

```http
PUT /{bucket}/{key}
GET /{bucket}/{key}
DELETE /{bucket}/{key}
HEAD /{bucket}/{key}
GET /{bucket}?list-type=2&prefix={prefix}&continuation-token={token}
POST /{bucket}/{key}?uploads             # multipart init
PUT /{bucket}/{key}?partNumber=N&uploadId={uid}  # multipart part
POST /{bucket}/{key}?uploadId={uid}      # multipart complete
DELETE /{bucket}/{key}?uploadId={uid}    # multipart abort
```

The internal mapping:

```
BUCKET RECORD
  key:    bucket:{tenant_id}:{bucket_name}
  value:  {
    bucket_id        UUID
    tenant_id        UUID
    inode_root       UUID        // root inode for objects in this bucket
    versioning       enabled | suspended | disabled
    encryption_policy {kms_key_id, algorithm}
    lifecycle_rules  bytes
    region_policy    string
  }

MULTIPART UPLOAD RECORD
  key:    multipart:{bucket_id}:{key}:{upload_id}
  value:  {
    upload_id        UUID
    parts            [{part_number, etag, size, inode_id}]
    initiated_at     timestamp
    expires_at       timestamp
  }
```

The RPC surface (between regions):

```
rpc ship_extent(extent_id, bytes, manifest_ref) returns (ack)
rpc ship_journal_record(journal_record) returns (ack)
rpc reconcile_snapshot(namespace, snapshot_id) returns (extent_state_diff)
```

#### Life of a Request

##### S3 PUT (multipart, 5 GB object):
1. Client POSTs `/{bucket}/{key}?uploads` to initiate. Gateway authenticates the request against IAM; checks bucket ACL; mints an upload_id.
2. Client splits the 5 GB object into 1000 × 5 MB parts. For each part, PUTs `/{bucket}/{key}?partNumber=N&uploadId={uid}`.
3. Gateway per-part: opens an internal file via the client library against a multipart-staging directory; streams the part bytes through; closes; records the part's ETag (MD5) and inode_id in the multipart record.
4. Client POSTs `/{bucket}/{key}?uploadId={uid}` to complete, sending the ordered list of part ETags.
5. Gateway validates the ETag list; opens the destination file via the client library; concatenates the part inodes into one logical file.
6. Gateway writes the final file's metadata into the bucket's listing index.
7. Gateway returns 200 OK with the complete ETag.

#### Failure Modes

- **Auth latency.** Per-request IAM check adds tens of milliseconds. Mitigation: IAM caches signed tokens for the request's lifetime; bucket-level ACL cached for short TTL; KMS calls cached at the cluster-monitor-issued lease level.
- **Listing cost.** `GET /{bucket}?list-type=2` over a bucket with billions of objects is expensive. Mitigation: listing index is paginated; continuation tokens are server-side cursors; max page size 1000 by S3 convention.
- **Large-object multipart cleanup.** Multipart uploads abandoned by clients waste storage. Mitigation: multipart records have an expiry timestamp; Custodian (§5) periodically scans for expired records and unlinks their parts.
- **Cross-region replication semantics.** S3 cross-region replication via the gateway must respect tenant residency. Mitigation: gateway checks the bucket's region_policy before initiating any cross-region operation.

---

### §8. POSIX Gateway (NFS / SMB / CSI Driver)

#### Role

The POSIX gateway exposes the cluster via NFS, SMB, and Kubernetes CSI to applications that cannot or will not link the client library. It is a protocol translation tier — POSIX syscalls in, internal MDS/OSD RPCs out. The cost is the context switches and protocol-state overhead the thick client avoids; the benefit is a working solution for legacy applications.

#### Internal Components

Protocol heads (NFS, SMB, CSI) translate client calls into capability delegation requests and file-lock requests, which are executed via an embedded Client Library session.

#### Schema / API Surface

The gateway maps POSIX syscalls to internal RPCs:

| POSIX op | Maps to |
|---|---|
| `open` | MDS lookup + cap grant via client library |
| `read` | OSD read via client library |
| `write` | OSD write via client library |
| `fsync` | Flush write buffer; MDS fsync RPC |
| `close` | Cap release |
| `stat` | MDS stat (cap-cached if held) |
| `readdir` | MDS readdir |
| `rename` | MDS rename |
| NFS `LOCK` / `LOCKU` | MDS lock op |
| SMB `LeaseRequest` | MDS cap grant with delegation semantics |

#### Life of a Request

##### NFS `open` of /mnt/file.bin:
1. Application opens the path; NFS client kernel issues NFS LOOKUP RPCs to the NFS head.
2. NFS head, holding an internal client-library session for this NFS client, performs path resolution via MDS.
3. NFS head returns the file handle to the NFS client.
4. Application reads or writes; each NFS READ/WRITE RPC translates to a client-library read/write.
5. NFS head batches small writes against the client-library write buffer to amortize the protocol overhead.

#### Failure Modes

- **Protocol-level retry semantics.** NFS clients retry indefinitely on timeout. Mitigation: idempotent operations; duplicate-reply cache (DRC); cap epochs prevent stale ops from applying.
- **Lock semantics mismatch.** NFSv3 advisory locks don't match the MDS exclusive-cap semantics perfectly; SMB oplocks are an even worse match. Mitigation: the gateway translates as best it can; conflict resolution converts to cap recall; some workloads accept relaxed semantics in exchange for protocol compatibility.
- **Client-side caching of negative cookies.** NFS clients cache negative readdir results. Mitigation: NFS attribute timeout tuning; clients can be told to invalidate.

---

### §9. Geo-Replication Service (Async Log Shipper)

#### Role

The geo-replication service ships data and metadata changes from one region to another asynchronously. The architecture commits to *async* geo-replication by default — synchronous cross-region replication on every write is unacceptable on a latency budget at WAN distances.

The unit of replication is the sealed extent (for chunk data) and the journal record (for metadata). The shipper streams these to a peer region's applier, which writes them into the peer region's local cluster.

#### Internal Components

The extent shipper and metadata shippers read changes from the OSD and MDS logs, streaming them over the WAN channel to target region appliers, while tracking snapshot manifests.

#### Schema / API Surface

```
SNAPSHOT MANIFEST RECORD
  key:    snapshot:{namespace}:{snapshot_id}
  value:  {
    snapshot_id        UUID
    namespace          string
    parent_snapshot    UUID | null
    extents            [{extent_id, source_osd, length, checksum}]
    journal_window     {start_lsn, end_lsn}
    created_at         timestamp
    shipped_at         timestamp | null
  }

APPLIED-EXTENT RECORD
  key:    applied:{namespace}:{extent_id}
  value:  {
    extent_id          UUID
    applied_at         timestamp
    source_region      string
    target_osd         u32
  }
```

The RPC surface (between regions):

```
rpc ship_extent(extent_id, bytes, manifest_ref) returns (ack)
rpc ship_journal_record(journal_record) returns (ack)
rpc reconcile_snapshot(namespace, snapshot_id) returns (extent_state_diff)
```

#### Life of a Request

##### Ship a sealed extent:
1. Extent on source OSD is sealed (write path closes the extent).
2. Snapshot manifest manager observes the seal; records the extent in the current snapshot.
3. Extent shipper streams the bytes over the WAN channel to the target region's applier.
4. Applier writes the bytes to a target-region OSD.
5. Applier records the extent as applied; sends ack to the shipper.
6. On a periodic interval (every few seconds), the snapshot is closed and a new one is opened. Successfully-shipped snapshots advance the cluster-level RPO marker.

#### Failure Modes

- **RPO lag.** WAN channel is slow or congested. Mitigation: RPO is monitored; alert at threshold; for tenants with strict RPO, the gateway can refuse writes if RPO would exceed bound.
- **Snapshot manifest divergence.** Source and target disagree on what's been shipped. Mitigation: applied-extent records are idempotent; duplicate applies are no-ops. Periodic reconciliation compares snapshot manifests.
- **Failover replay storm.** During a regional failover, the target region must serve clients that previously talked to the source. Mitigation: failover is a planned operation; capacity is provisioned for the surge; some clients fail-over gradually.

The extent-sealing pattern is what makes the async replication tractable: shipping a sealed extent is shipping immutable bytes, with no risk of the source mutating them after the fact.

---

### Cross-Cutting Concerns

#### Metrics that matter

The metrics surface is large because the failure surface is large. The minimum set:

```
# PG state (gauges, labelled by pool_id, state)
dfs_pg_state_count{pool_id, state}

# Slow ops (counters, labelled by op_type, tenant_id)
dfs_osd_slow_op_total{op_type, tenant_id}

# Scrub errors (counters, labelled by pool_id, scrub_type)
dfs_scrub_errors_total{pool_id, scrub_type}

# Recovery throughput (gauge, bytes/sec, labelled by priority_class)
dfs_repair_bytes_per_sec{priority_class}

# Repair queue depth (gauge, work items, labelled by priority_class)
dfs_repair_queue_depth{priority_class}

# Cap recall latency (histogram, labelled by mds_shard_id)
dfs_mds_cap_recall_latency_seconds_bucket{mds_shard_id, le}

# MON quorum health (gauge, boolean per node)
dfs_mon_quorum_member_up{node_id}

# OSD up/down/in/out (counts and rate-of-change)
dfs_osd_state_count{state}
dfs_osd_state_transitions_total{from_state, to_state}

# Near-full thresholds (gauges)
dfs_osd_bytes_used_ratio{osd_id}
dfs_pool_bytes_used_ratio{pool_id}

# dmClock allocations (per tenant, per op class)
dfs_dmclock_reservation_iops{tenant_id, op_class}
dfs_dmclock_weight{tenant_id, op_class}
dfs_dmclock_limit_iops{tenant_id, op_class}
```

#### Per-tenant isolation

Tenant isolation rests on three composing layers:
1. **dmClock at the OSD** allocates I/O budget: Reservation guarantees a tenant's floor; Weight gives proportional share of excess; Limit caps the ceiling.
2. **Capability vectors scoped per tenant** mean the MDS's locks and caps are namespaced; one tenant cannot recall another tenant's caps.
3. **KMS per-tenant DUKs** make the at-rest encryption keys tenant-bounded: a compromised tenant's keys do not give access to another tenant's data, and a GDPR erasure request destroys only the requesting tenant's keys.

#### Deploy unit and blast radius

The deployment unit is a *cell*. A cell is a self-contained file system instance with its own MDS fleet, block layer, monitor quorum, and OSD fleet. Cells are sized for blast radius: a single cell holds 1–10 PB and 10^9–10^10 chunks.

Within a cell, the failure domains are: rack → host → disk. CRUSH-style placement rules ensure no PG's replicas all land in the same rack.

#### Security model

- **At-rest encryption** has two layers. Disk-level dm-crypt with per-OSD keys protects against physical drive theft. Per-tenant DUKs in the KMS protect against operator key-material exposure and enable crypto-shredding.
- **In-flight encryption** between daemons uses msgr2, cephx, or TLS 1.3 depending on the deployment.
- **Audit logs** are append-only on a separate cluster. Every privileged operation — cap recall, lease grant, OSD mark-down, tier transition — logs a record.
- **Crypto-shredding for GDPR** is the only viable erasure semantics on append-only logs and EC-distributed extents. On erasure request, the DUK is destroyed in the KMS. The ciphertext bits remain on disk but are permanently unreadable.

---

### §10. Open Questions and Limitations

#### Optimal EC stripe widths
The crossover between LRC, ClayCodes, and deep Reed-Solomon under continuous failure conditions is not solved. Wider stripes maximize storage density but cripple the network during repair. LRC and ClayCodes mitigate this, but the optimal sub-packetization level for ClayCodes versus the local-parity-group size for LRC depends on the cluster's failure rate, network topology, and CPU budget.

#### CRUSH vs lookup at 10^13 objects
As clusters breach 10 EB, the deterministic beauty of CRUSH meets propagation-storm limits and the per-object footprint of pure lookup meets DB-fleet limits. The hybrid this essay defends is the current industry compromise, but it is not obviously the right answer at the next order of magnitude. The community has not converged on whether intelligent thick-client deterministic routing or centralized thin-client database routing scales further.

#### Small-file efficiency at exabyte scale
Packing billions of 10 KB files efficiently remains a fundamental physical challenge. Padding small files to minimum extent sizes (e.g., 64 KB) wastes petabytes. Aggregation strategies (needle packing, WAL batching) introduce their own failure modes. The metadata-to-data ratio for small-file workloads remains painful.

#### Safe global rebalancing under continuous failure
In a cluster where 500 disks fail per day, "stable steady state" does not exist. Capacity smoothing competes with critical reconstruction; both compete with client I/O. Guaranteeing that rebalancing doesn't accidentally trigger secondary failures via I/O exhaustion requires aggressive dmClock tuning, dynamic budget elevation, and human-in-the-loop checks.

#### Subtree partitioning vs hash sharding for MDS at very large scale
CephFS's dynamic subtree partitioning preserves traversal locality but requires an active migration controller and is vulnerable to thrash on workloads with shifting hotspots. Tectonic's hash sharding gives trivial load balancing but destroys readdir locality. At 10^11 files, neither answer is obviously superior.

---

## 2. Repository Architecture and Dependency Model

This section documents the structure of the accompanying 15-module Gradle codebase, reconciling theoretical abstractions with concrete software subprojects.

### The Two-Axis Mapping

This repository organizes code along two axes:
- **Phase** — the order it was built (foundation → storage backend → control plane → ops). Phases represent *learning order*, not deployment.
- **Concern** — what architectural layer the module implements (placement, consistency, durability, QoS, etc.). Concerns are about *what the code actually does*.

### The Disaggregated Control / Data Plane Layout

```
                       ┌─────────────────────────────────┐
                       │       CONTROL PLANE              │
                       │  (sharded namespace, placement,  │
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

### Module Dependency Graph

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

Dependency design invariants:
- **`dfs-qos` does NOT depend on `dfs-custodian`**. The scheduler is unaware of repair semantics; the Custodian sits above it and uses it as a generic priority dispatcher. This is what lets `dfs-qos` be tested without spinning up a fake Custodian.
- **`dfs-monitor` does NOT depend on `dfs-mds`**. The monitor knows about OSDs, leases, and durability events — not about file-system inodes.
- **`dfs-simulator` is the only module that depends on everything**. It exists specifically to compose the parts and exercise cross-module sequences in tests.

---

### Wiki Concept → Module Map

| Wiki concept | Module | Primary classes |
|---|---|---|
| `concepts/crush-placement-algorithm` | `dfs-crush` | `Crush`, `StrawSelector`, `CrushMap` |
| `patterns/hybrid-deterministic-lookup-placement` | `dfs-placement` | `Placement`, `BlockLayer`, `PgLocation` |
| `concepts/chunk-lease` | `dfs-lease` | `LeaseService`, `ChunkLease` |
| `concepts/extent-sealing` | `dfs-lease` | `ExtentService`, `Extent`, `ExtentStatus` |
| `concepts/bitmap-allocator` | `dfs-allocator` | `BitmapAllocator`, `Range` |
| `tradeoffs/posix-fs-vs-raw-block-backend` | `dfs-storage` | `Osd` (BlueStore-style two write paths) |
| `concepts/erasure-coding` | `dfs-erasure` | `Replication`, `ReedSolomon` |
| `concepts/local-reconstruction-codes` | `dfs-erasure` | `LRC` |
| `concepts/capability-vector` | `dfs-mds` | `CapabilityVector`, `MdsCluster.open/recall` |
| `concepts/dynamic-subtree-partitioning` | `dfs-mds` | `SubtreePartitioner` |
| (no single page; tech-spec) | `dfs-monitor` | `Monitor`, `DurabilityEvent`, `OsdStatus` |
| `concepts/dmclock-qos` | `dfs-qos` | `DmClockScheduler`, `QosClass` |
| `patterns/custodian-background-control-plane` | `dfs-custodian` | `Custodian`, `RepairScanner`, `PriorityClass`, `WorkItem` |
| `concepts/key-shredding` | `dfs-security` | `Kms`, `DukId`, `KeyDestroyedException` |

---

## 3. Code Companion & Implementation Gaps

### Blog Part → Code Map

- **Part 1 — Disaggregate Control from Data**: The dependency rules themselves enforce this — see `0004-15-modules-by-concern.md`. `dfs-node/.../NodeApi.java` composes the data-plane write path.
- **Part 3 — Sharded Namespace**: `dfs-mds` (the namespace shard owner). The transactional KV store is stubbed to `ConcurrentHashMap<String, Inode>` (see `0002-in-memory-substrates.md`). Key classes: `MdsCluster.java` and `SubtreePartitioner.java`.
- **Part 4 — Hybrid Placement**: `dfs-crush` for the deterministic hop, `dfs-placement` for the PG lookup table, composed by `dfs-node`. Key classes: `Crush.java`, `StrawSelector.java`, `Placement.java`, `BlockLayer.java`.
- **Part 5 — Raw-Block Storage**: `dfs-allocator` (the bitmap cascade) and `dfs-storage` (the user-space OSD). Key classes: `BitmapAllocator.java` and `Osd.java` (WAL packing, CoW metadata transaction stubbed onto `ConcurrentSkipListMap`).
- **Part 6 — Replication, LRC**: `dfs-erasure`. Key classes: `Replication.java` and `ReedSolomon.java` (XOR parity stub - see `0003-xor-parity-stub-not-galois.md`).
- **Part 7 — Consistency: Leases & Sealing**: `dfs-lease`. Key classes: `LeaseService.java` and `ExtentService.java`.
- **Part 8 — Capability Vectors & Subtree Partitions**: `dfs-mds`. Key classes: `CapabilityVector.java` and `SubtreePartitioner.java`.
- **Part 9 — Custodians & dmClock**: `dfs-custodian` (scanner/dispatcher), `dfs-qos` (dmClock scheduler), and `dfs-monitor` (cluster state tracker). Key classes: `Custodian.java`, `RepairScanner.java`, `DmClockScheduler.java`.
- **Part 12 — Crypto-Shredding**: `dfs-security`. Key classes: `Kms.java` (uses AES-GCM; see `0005-aes-gcm-real-crypto.md`).

---

### Gaps (Blog claims the code does not implement)

These are design mechanisms described in the theoretical blueprint that are represented as stubs or departures from production in the Java teaching codebase:

| Blog reference | Status in code | Where it shows up |
|---|---|---|
| **ClayCodes** | Not implemented. | `dfs-erasure` only contains `Replication`, `ReedSolomon` (stub), and `LRC` (cost-only). |
| **True Reed-Solomon** | Replaced by an XOR-with-rotation stub. | `ReedSolomon.java`. The decoder cannot reconstruct missing data shards from parities. |
| **Paxos-replicated monitor** | `dfs-monitor` is single-node, in-process. | `Monitor.java`. No quorum consensus or log replication. |
| **Production KMS** | Keys live in heap. | `Kms.java`. JVM core-dump leaks key material (no hardware security module). |
| **End-to-end wired data path** | `NodeApi.put` does not persist bytes through `Osd`. | `NodeApi.java` interacts with `ExtentService` in-memory logs; the OSD is reachable but never receives data writes. |
| **RocksDB on raw block via BlueFS** | OSD uses `ConcurrentSkipListMap` and `HashMap`. | `Osd.java`. No LSM compaction, no WAL replay, no crash recovery. |
| **Transactional KV under MDS** | Namespace uses `ConcurrentHashMap`. | `MdsCluster.java`. No cross-shard transactions. |
| **Cross-shard rename atomicity** | Single-node in-process MDS has no shard boundaries. | `MdsCluster.java` handles renames in a simple local transaction. |
| **Network repair traffic** | Repair is in-process invocation. | `Custodian.java`. Bandwidth math is calculated arithmetic; no bytes are shipped on the wire. |
| **Map-propagation micro-bursts** | Map versioning exists but client refresh storms don't. | `Monitor.java`. No remote clients. |

---

## 4. First 30 Minutes: Repository Getting Started

This repository is the Java 17 companion implementation for the CSE wiki topic **Ceph/GFS-class distributed file systems**. 

### Repository Structure
The repo is structured as a Gradle multi-module project with 15 modules across 4 phases:
- **Phase 1 — Foundation:** `dfs-common`, `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node`
- **Phase 2 — Storage backend:** `dfs-allocator`, `dfs-storage`, `dfs-erasure`
- **Phase 3 — Control plane:** `dfs-mds`, `dfs-monitor`, `dfs-qos`, `dfs-custodian`
- **Phase 4 — Ops + simulator:** `dfs-simulator`, `dfs-metrics`, `dfs-security`

Every module is small, single-concept, and has its own `src/test/` with contract-style tests. There are 239 tests total across the repo.

### Building and Running Tests
To build and execute all 239 tests in the repository:
```sh
cd ~/code-all/distributed-file-system
# Full build
./gradlew build --console=plain

# Run one module's tests in isolation
./gradlew :dfs-crush:test
```

---

## 5. Core Reference Implementation (Java Sources)

This section hosts the complete, finalized source code for the core logical components of the distributed file system.

### StrawSelector.java

```java
package com.hkg.dfs.crush;

import java.util.List;

/**
 * Deterministic weighted child selection: for each candidate, draw a
 * pseudo-random straw of length {@code hash(seed, name, replicaIdx) * weight}
 * and pick the longest. This is the property that lets CRUSH avoid
 * mass-reshuffling when buckets are added or removed
 * ({@code wiki/concepts/crush-placement-algorithm}).
 */
public final class StrawSelector {

    public CrushBucket select(List<CrushBucket> candidates, long seed, int replicaIdx) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no candidates");
        }
        CrushBucket best = null;
        double bestStraw = -Double.MAX_VALUE;
        for (CrushBucket c : candidates) {
            long h = hash(seed, c.name().hashCode(), replicaIdx);
            double u = (double) (h & 0x7FFFFFFFFFFFFFFFL) / Long.MAX_VALUE;
            if (u <= 0.0) u = 1e-15;
            double straw = Math.log(u) / c.weight();
            if (straw > bestStraw) {
                bestStraw = straw;
                best = c;
            }
        }
        return best;
    }

    static long hash(long seed, int nameHash, int replicaIdx) {
        long x = seed * 0xBF58476D1CE4E5B9L + nameHash * 0x94D049BB133111EBL + replicaIdx * 0xD1342543DE82EF95L;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
```

---

### Osd.java

```java
package com.hkg.dfs.storage;

import com.hkg.dfs.common.ObjectId;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.zip.CRC32C;

/**
 * BlueStore-style Object Storage Device. Two write paths:
 * <ul>
 *   <li>Large writes: copy-on-write into a fresh extent + metadata commit.</li>
 *   <li>Small writes: in-memory WAL queue; background flush merges into an extent.</li>
 * </ul>
 * A {@link ConcurrentSkipListMap} stands in for the RocksDB metadata. Every
 * stored block carries a CRC32c and is verified on read.
 */
public final class Osd {
    private static final int SMALL_WRITE_THRESHOLD = 4096;

    private final ConcurrentSkipListMap<String, byte[]> data = new ConcurrentSkipListMap<>();
    private final Map<String, Integer> crc = new HashMap<>();
    private final Deque<WalEntry> wal = new ArrayDeque<>();
    private final Map<ObjectId, Location> objectLocations = new HashMap<>();

    public synchronized void writeLarge(String extentId, long offset, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        String key = key(extentId, offset);
        data.put(key, copy);
        crc.put(key, crc32c(copy));
    }

    public synchronized void writeSmall(ObjectId obj, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        if (bytes.length > SMALL_WRITE_THRESHOLD) {
            throw new IllegalArgumentException("use writeLarge for >" + SMALL_WRITE_THRESHOLD + "B");
        }
        wal.add(new WalEntry(obj, Arrays.copyOf(bytes, bytes.length)));
    }

    public synchronized Map<ObjectId, Location> flushDeferred(String extentId, long baseOffset) {
        Map<ObjectId, Location> flushed = new HashMap<>();
        long off = baseOffset;
        while (!wal.isEmpty()) {
            WalEntry e = wal.poll();
            writeLarge(extentId, off, e.bytes);
            Location loc = new Location(extentId, off, e.bytes.length);
            objectLocations.put(e.obj, loc);
            flushed.put(e.obj, loc);
            off += e.bytes.length;
        }
        return flushed;
    }

    public synchronized Optional<Location> lookup(ObjectId obj) {
        return Optional.ofNullable(objectLocations.get(obj));
    }

    public synchronized byte[] read(String extentId, long offset, int length) {
        String key = key(extentId, offset);
        byte[] blob = data.get(key);
        if (blob == null) throw new IllegalStateException("not found: " + key);
        int expected = crc.getOrDefault(key, 0);
        int actual = crc32c(blob);
        if (expected != actual) {
            throw new ChecksumMismatchException(key, expected, actual);
        }
        int n = Math.min(length, blob.length);
        return Arrays.copyOfRange(blob, 0, n);
    }

    public synchronized void corruptForTest(String extentId, long offset) {
        String key = key(extentId, offset);
        byte[] blob = data.get(key);
        if (blob == null) return;
        blob[0] = (byte) (blob[0] ^ 0xFF);
    }

    public synchronized int walSize() { return wal.size(); }

    private static String key(String extentId, long offset) {
        return extentId + "@" + offset;
    }

    private static int crc32c(byte[] b) {
        CRC32C c = new CRC32C();
        c.update(b);
        return (int) c.getValue();
    }

    private record WalEntry(ObjectId obj, byte[] bytes) {}
    public record Location(String extentId, long offset, int length) {}
}
```

---

### SubtreePartitioner.java

```java
package com.hkg.dfs.mds;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic subtree partitioning across MDS shards. Each path prefix is owned
 * by exactly one MDS. Migrations are explicit: {@link #migrate(String, int, int)}.
 */
public final class SubtreePartitioner {
    private final Map<String, Integer> ownership = new HashMap<>();

    public synchronized void assign(String subtree, int mdsId) {
        if (subtree == null || subtree.isBlank()) throw new IllegalArgumentException("subtree");
        if (mdsId < 0) throw new IllegalArgumentException("mdsId");
        ownership.put(subtree, mdsId);
    }

    public synchronized Optional<Integer> ownerOf(String path) {
        String best = null;
        for (String k : ownership.keySet()) {
            if (matches(path, k)) {
                if (best == null || k.length() > best.length()) best = k;
            }
        }
        return best == null ? Optional.empty() : Optional.of(ownership.get(best));
    }

    private boolean matches(String path, String prefix) {
        if (path.equals(prefix)) return true;
        if (prefix.equals("/")) return true;
        String normPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return path.startsWith(normPrefix);
    }

    public synchronized void migrate(String subtree, int fromMds, int toMds) {
        Integer cur = ownership.get(subtree);
        if (cur == null) throw new IllegalStateException("no owner for " + subtree);
        if (cur != fromMds) {
            throw new IllegalStateException("subtree owner is " + cur + " not " + fromMds);
        }
        ownership.put(subtree, toMds);
    }

    public synchronized int subtreeCount() {
        return ownership.size();
    }
}
```

---

### Monitor.java

```java
package com.hkg.dfs.monitor;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.lease.ChunkLease;
import com.hkg.dfs.lease.LeaseService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory cluster monitor. Tracks OSD heartbeats, manages lease
 * lifecycle, publishes a versioned cluster map, and emits durability
 * events when a PG drops below its replication floor.
 */
public final class Monitor {
    private final LeaseService leases;
    private final ConcurrentHashMap<OsdId, OsdStatus> status = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OsdId, Integer> missedBeats = new ConcurrentHashMap<>();
    private final Map<PgId, List<OsdId>> pgMap = new HashMap<>();
    private final List<DurabilityEvent> events = new ArrayList<>();
    private final Set<PgId> reportedBelowFloor = new HashSet<>();
    private long mapVersion = 0;
    private final int missThreshold;
    private final int durabilityFloor;

    public Monitor(LeaseService leases, int missThreshold, int durabilityFloor) {
        this.leases = leases;
        this.missThreshold = missThreshold;
        this.durabilityFloor = durabilityFloor;
    }

    public void register(OsdId osd) {
        status.put(osd, OsdStatus.UP);
        missedBeats.put(osd, 0);
    }

    public ChunkLease grantLease(ChunkId chunk, OsdId primary) {
        return leases.grant(chunk, primary);
    }

    public void revokeLease(ChunkId chunk) {
        leases.revoke(chunk);
    }

    public synchronized long publishMap(Map<PgId, List<OsdId>> snapshot) {
        pgMap.clear();
        pgMap.putAll(snapshot);
        mapVersion++;
        return mapVersion;
    }

    public long mapVersion() { return mapVersion; }

    public OsdStatus statusOf(OsdId osd) { return status.getOrDefault(osd, OsdStatus.UP); }

    public synchronized void heartbeat(OsdId osd) {
        if (!status.containsKey(osd)) register(osd);
        missedBeats.put(osd, 0);
        status.put(osd, OsdStatus.UP);
    }

    public synchronized void tick() {
        for (OsdId o : new ArrayList<>(status.keySet())) {
            int v = missedBeats.getOrDefault(o, 0) + 1;
            missedBeats.put(o, v);
            if (v >= missThreshold) {
                status.put(o, OsdStatus.DOWN);
            }
        }
        emitDurabilityEvents();
    }

    private void emitDurabilityEvents() {
        for (Map.Entry<PgId, List<OsdId>> e : pgMap.entrySet()) {
            int alive = 0;
            for (OsdId o : e.getValue()) {
                if (status.getOrDefault(o, OsdStatus.UP) == OsdStatus.UP) alive++;
            }
            if (alive < durabilityFloor) {
                if (reportedBelowFloor.add(e.getKey())) {
                    events.add(new DurabilityEvent(e.getKey(), alive, durabilityFloor));
                }
            } else {
                reportedBelowFloor.remove(e.getKey());
            }
        }
    }

    public synchronized List<DurabilityEvent> drainEvents() {
        List<DurabilityEvent> out = List.copyOf(events);
        events.clear();
        return out;
    }
}
```

---

### DmClockScheduler.java

```java
package com.hkg.dfs.qos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * dmClock scheduler. Each class carries three virtual-time tags
 * (reservation, weight, limit). The scheduler runs in two phases:
 * <ol>
 *   <li>Reservation phase: dispatch tasks whose reservation tag is the
 *   smallest and below the current real time — these are guaranteed
 *   minimum capacity.</li>
 *   <li>Weight phase: among classes that are not above their limit tag,
 *   dispatch the one with the smallest weight tag.</li>
 * </ol>
 * Implements {@code wiki/concepts/dmclock-qos}.
 */
public final class DmClockScheduler {
    private final Map<String, QosClass> classes = new LinkedHashMap<>();
    private final Map<String, Deque<Op>> queues = new HashMap<>();
    private final Map<String, Tags> tags = new HashMap<>();
    private final Map<String, Long> served = new HashMap<>();
    private double virtualTime = 0;

    public synchronized void addClass(QosClass cls) {
        if (classes.containsKey(cls.name())) {
            throw new IllegalStateException("class exists: " + cls.name());
        }
        classes.put(cls.name(), cls);
        queues.put(cls.name(), new ArrayDeque<>());
        tags.put(cls.name(), new Tags(0, 0, 0));
        served.put(cls.name(), 0L);
    }

    public synchronized CompletableFuture<Void> submit(String className, Runnable op) {
        QosClass c = classes.get(className);
        if (c == null) throw new IllegalStateException("no class: " + className);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Tags t = tags.get(className);
        double now = virtualTime;
        double reqR = Math.max(t.r, now) + (c.reservation() > 0 ? 1.0 / c.reservation() : Double.POSITIVE_INFINITY);
        double reqW = Math.max(t.w, now) + 1.0 / c.weight();
        double reqL = Math.max(t.l, now) + 1.0 / c.limit();
        tags.put(className, new Tags(reqR, reqW, reqL));
        queues.get(className).add(new Op(op, done, reqR, reqW, reqL));
        return done;
    }

    /**
     * Run one scheduling step. Returns the name of the class served, or
     * null when nothing was available.
     */
    public synchronized String dispatch() {
        virtualTime += 1.0;
        String chosen = null;
        double bestR = Double.POSITIVE_INFINITY;
        for (var e : classes.entrySet()) {
            var q = queues.get(e.getKey());
            if (q.isEmpty()) continue;
            Op head = q.peek();
            if (head.r <= virtualTime && head.r < bestR) {
                bestR = head.r;
                chosen = e.getKey();
            }
        }
        if (chosen == null) {
            double bestW = Double.POSITIVE_INFINITY;
            for (var e : classes.entrySet()) {
                var q = queues.get(e.getKey());
                if (q.isEmpty()) continue;
                Op head = q.peek();
                if (head.l > virtualTime) continue;
                if (head.w < bestW) {
                    bestW = head.w;
                    chosen = e.getKey();
                }
            }
        }
        if (chosen == null) return null;
        Op op = queues.get(chosen).poll();
        served.merge(chosen, 1L, Long::sum);
        try {
            op.run.run();
            op.done.complete(null);
        } catch (RuntimeException x) {
            op.done.completeExceptionally(x);
        }
        return chosen;
    }

    public synchronized long served(String className) {
        return served.getOrDefault(className, 0L);
    }

    public synchronized int queueDepth(String className) {
        var q = queues.get(className);
        return q == null ? 0 : q.size();
    }

    private record Tags(double r, double w, double l) {}

    private record Op(Runnable run, CompletableFuture<Void> done, double r, double w, double l) {}
}
```

---

### MdsCluster.java

```java
package com.hkg.dfs.mds;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MDS cluster with per-path capability tracking and a simple inode cache.
 * The cluster is split into MDS shards by {@link SubtreePartitioner}.
 * Implements the dynamic-subtree partitioning + cap recall pattern from
 * {@code wiki/my-explanations/design-distributed-file-system}.
 */
public final class MdsCluster {
    private final ConcurrentHashMap<String, Inode> inodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CapHolder> caps = new ConcurrentHashMap<>();
    private final AtomicLong inodeSeq = new AtomicLong(1);
    private final SubtreePartitioner partitioner = new SubtreePartitioner();

    public Inode mkdir(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path");
        long parentId = checkParentAndGetId(path);
        long id = inodeSeq.getAndIncrement();
        Inode inode = new Inode(id, parentId, nameOf(path), InodeType.DIR);
        inodes.put(path, inode);
        return inode;
    }

    public Inode create(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path");
        long parentId = checkParentAndGetId(path);
        long id = inodeSeq.getAndIncrement();
        Inode inode = new Inode(id, parentId, nameOf(path), InodeType.FILE);
        inodes.put(path, inode);
        return inode;
    }

    public Optional<Inode> stat(String path) {
        return Optional.ofNullable(inodes.get(path));
    }

    /**
     * Issue a capability vector for the given path to {@code clientId}.
     * If another client holds a conflicting (write/exclusive) cap, recall
     * it first.
     */
    public synchronized CapabilityVector open(String path, String clientId, CapabilityVector requested) {
        CapHolder existing = caps.get(path);
        if (existing != null && !existing.clientId.equals(clientId) && conflicts(existing.cap, requested)) {
            recall(path);
        }
        caps.put(path, new CapHolder(clientId, requested));
        return requested;
    }

    public void recall(String path) {
        caps.remove(path);
    }

    public Optional<CapabilityVector> heldBy(String path, String clientId) {
        CapHolder h = caps.get(path);
        if (h == null || !h.clientId.equals(clientId)) return Optional.empty();
        return Optional.of(h.cap);
    }

    public SubtreePartitioner partitioner() {
        return partitioner;
    }

    public Map<String, Inode> snapshot() {
        return Map.copyOf(inodes);
    }

    public void rename(String fromPath, String toPath) {
        if (toPath == null || !toPath.startsWith("/")) throw new IllegalArgumentException("toPath");
        Inode prev = inodes.remove(fromPath);
        if (prev == null) throw new IllegalStateException("missing: " + fromPath);
        long parentId = checkParentAndGetId(toPath);
        Inode renamed = new Inode(prev.inodeId(), parentId, nameOf(toPath), prev.type());
        inodes.put(toPath, renamed);
        caps.remove(fromPath);
    }

    private long checkParentAndGetId(String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return 0;
        String parentPath = path.substring(0, slash);
        Inode parent = inodes.get(parentPath);
        if (parent == null) {
            throw new IllegalStateException("parent directory missing: " + parentPath);
        }
        if (parent.type() != InodeType.DIR) {
            throw new IllegalStateException("parent is not a directory: " + parentPath);
        }
        return parent.inodeId();
    }

    private String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    private boolean conflicts(CapabilityVector held, CapabilityVector wanted) {
        return held.hasExclusive() || wanted.hasExclusive() || wanted.hasWrite() || held.hasWrite();
    }

    private record CapHolder(String clientId, CapabilityVector cap) {}
}
```

---

### Custodian.java

```java
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
```

---

### NodeApi.java

```java
package com.hkg.dfs.node;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.crush.BucketType;
import com.hkg.dfs.crush.Crush;
import com.hkg.dfs.lease.ExtentService;
import com.hkg.dfs.lease.LeaseService;
import com.hkg.dfs.placement.BlockLayer;
import com.hkg.dfs.placement.PgLocation;
import com.hkg.dfs.placement.Placement;

import java.util.List;

/**
 * Composes CRUSH + Placement + Lease + Extent to provide an end-to-end
 * {@link #put(ObjectId, byte[])}: hash → PG → OSD set → grant primary lease →
 * append to extent → seal.
 */
public final class NodeApi {
    private final Placement placement;
    private final BlockLayer blockLayer;
    private final Crush crush;
    private final LeaseService leases;
    private final ExtentService extents;
    private final int replicationFactor;

    public NodeApi(Placement placement, BlockLayer blockLayer, Crush crush,
                   LeaseService leases, ExtentService extents, int replicationFactor) {
        this.placement = placement;
        this.blockLayer = blockLayer;
        this.crush = crush;
        this.leases = leases;
        this.extents = extents;
        this.replicationFactor = replicationFactor;
    }

    public PutResult put(ObjectId obj, byte[] bytes) {
        PgId pg = placement.objectToPg(obj);
        PgLocation loc = blockLayer.lookup(pg).orElseGet(() -> {
            List<OsdId> osds = crush.place(obj, replicationFactor, BucketType.RACK);
            return blockLayer.putInitial(pg, osds);
        });
        ChunkId chunk = ChunkId.of(Math.abs(obj.value().hashCode()));
        leases.grant(chunk, loc.primary());
        String extentId = "ext-" + pg.value();
        try {
            extents.get(extentId);
        } catch (IllegalStateException missing) {
            extents.open(extentId);
        }
        extents.append(extentId, bytes);
        return new PutResult(pg, loc.osds(), chunk, extentId, bytes.length);
    }

    public record PutResult(PgId pg, List<OsdId> osds, ChunkId chunk, String extentId, int writtenBytes) {}
}
```

---

## 6. System Glossary

A-Z directory of load-bearing terminology mapping to specific codebase types.

### Allocation unit
The fixed size of a block tracked by the bitmap allocator. 4 KB on SSD, 64 KB on HDD in BlueStore; configurable in `dfs-allocator.BitmapAllocator`.

### ABS checkpointing
Asynchronous Barrier Snapshotting — not used in this repo (no Flink). Mentioned only in the wiki.

### AES-GCM
The authenticated encryption mode `dfs-security.Kms` uses for crypto-shredding (`AES/GCM/NoPadding`, 128-bit tag, 96-bit IV).

### Bitmap allocator
Free-space tracker. One bit per allocation unit; L1 summary bitmap for skip-fast-forward. Implemented in `dfs-allocator.BitmapAllocator`.

### BlueStore
Ceph's raw-block user-space storage backend. This repo's `dfs-storage.Osd` is a teaching-grade approximation.

### Block Layer
The lookup tier mapping a placement group to its physical OSDs. Implemented in `dfs-placement.BlockLayer`.

### Bytes
`dfs-common.Bytes` — immutable byte-array wrapper that defensively copies on construction and read.

### Capability vector (cap)
Fine-grained MDS delegation allowing a client to cache and buffer locally. `dfs-mds.CapabilityVector`.

### ChunkId
`dfs-common.ChunkId(long)` — identifier for a fixed-size chunk of an object.

### Chunk lease
Time-bounded grant from the monitor to a primary OSD authorising it to serialise writes for a chunk. `dfs-lease.LeaseService`.

### ClayCodes
MSR-optimal erasure code. Not implemented in this repo.

### Cluster map
The plane-boundary data structure the monitor publishes via `dfs-monitor.Monitor.publishMap`. Versioned by `mapVersion`.

### Control plane
The set of modules that own placement decisions, leases, monitoring, and background work: `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-mds`, `dfs-monitor`, `dfs-qos`, `dfs-custodian`.

### Copy-on-write (CoW)
Large-write path in `dfs-storage.Osd.writeLarge`: write to a fresh extent slot, then commit the metadata transaction linking it to the object. Avoids the journaling double-write of POSIX file systems.

### CRC32c
Castagnoli polynomial CRC. `dfs-storage.Osd` stores one per blob and verifies on read.

### CRUSH
Controlled Replication Under Scalable Hashing. Pseudo-random deterministic placement function. `dfs-crush.Crush.place(...)`.

### Custodian
Stateless background control loop driving scrub/repair/rebalance. `dfs-custodian.Custodian`.

### Data plane
The set of modules that own bytes: `dfs-allocator`, `dfs-storage`, `dfs-erasure`. Receives placement decisions and writes/reads bytes.

### dmClock
Multi-tenant proportional-share I/O scheduler. `dfs-qos.DmClockScheduler`. Three tags per class: reservation, weight, limit.

### DukId
`dfs-security.DukId(TenantId, ObjectId)` — identifier of a Data Unique Key in the KMS for crypto-shredding.

### Durability event
`dfs-monitor.DurabilityEvent(PgId, currentReplicas, floor)` — emitted when a placement group's live-replica count falls below the configured floor.

### Durability floor
The configured minimum replica count below which a placement group triggers a durability event. Held in `Monitor` constructor argument `durabilityFloor`.

### Dynamic subtree partitioning
Migration of authority for a directory subtree between MDS shards based on load. `dfs-mds.SubtreePartitioner`.

### Erasure coding (EC)
A family of redundancy schemes (Reed-Solomon, LRC, ClayCodes) implemented (some as stubs) in `dfs-erasure`.

### Extent
`dfs-lease.Extent(extentId, status, length)` — append-only byte sequence. Status is `OPEN` or `SEALED`.

### Extent sealing
Closing an extent at its last universally-committed length on primary failure. Future writes roll forward to a new extent. `dfs-lease.ExtentService.seal`.

### Failure domain
A topology level in the CRUSH map that placement must spread across (rack, host, AZ). `dfs-crush.BucketType`.

### Foundation phase
Phase 1 of the build plan: `dfs-common`, `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node`. Makes the minimal end-to-end PUT work.

### Generation
`dfs-common.Generation(long)` — monotonic version tag for a PG mapping. Bumped on every BlockLayer update.

### Global parity
In an LRC `(k, l, g)` scheme: a parity block computed over all k data blocks. Used when local-group repair is insufficient.

### Heartbeat
`dfs-monitor.Monitor.heartbeat(osdId)` – an OSD's signal of liveness. Missing `missThreshold` consecutive heartbeats marks the OSD `DOWN`.

### Hybrid placement
Two-tier placement: deterministic hash to a PG (cheap, local computation) + lookup of PG → OSDs in a small KV store (operationally smooth).

### Idempotency
Not used in this repo.

### Implementations section
Every wiki concept page has an `## Implementations` section linking to this repo's module.

### Inode
`dfs-mds.Inode(inodeId, parentId, name, InodeType)` — file-system metadata record. `InodeType` is `FILE` or `DIR`.

### KMS
Key Management Service. `dfs-security.Kms` — in-process AES-GCM implementation with key destruction = crypto-shredding.

### Key shredding
The pattern of encrypting data with a per-subject key and destroying the key to satisfy GDPR right-to-be-forgotten. `dfs-security.Kms.destroyDuk`.

### LRC (Local Reconstruction Codes)
EC scheme that adds local parity groups so single-block repair stays inside one rack-aware group. `dfs-erasure.LRC`.

### Lease
See **chunk lease**. The dfs-lease module exposes `grant`, `renew`, `revoke`, `get`.

### Limit (dmClock tag)
The hard ceiling on a class's I/O rate. Set per `QosClass`.

### Local parity group
In LRC: a subset of k data blocks plus one parity covering only that subset. Repairing a single failed data block reads from inside the group.

### MDS (Metadata Server)
Owner of POSIX namespace + capability vectors. `dfs-mds.MdsCluster`.

### Module
A Gradle subproject. The repo has 15 (`dfs-common`, `dfs-crush`, etc.). Each has its own `build.gradle`, `src/main`, `src/test`.

### Monitor
Cluster-monitor abstraction: tracks OSD heartbeats, grants leases, publishes maps, emits durability events. `dfs-monitor.Monitor`.

### MSR codes
Minimum Storage Regenerating codes. ClayCodes are MSR-optimal; not implemented in this repo.

### NodeApi
`dfs-node.NodeApi.put(obj, bytes)` — the end-to-end composition of CRUSH + placement + lease + extent.

### OSD (Object Storage Device)
The storage daemon: owns the raw block device, tracks free space, serves reads/writes. `dfs-storage.Osd`.

### OsdId
`dfs-common.OsdId(int)` — identifier.

### PG (Placement Group)
A logical bucket of objects. The hybrid placement scheme: object → PG (hash) → OSDs (KV lookup).

### PgId
`dfs-common.PgId(int)` — identifier.

### PgLocation
`dfs-placement.PgLocation(Generation, List<OsdId>)` — what a PG currently maps to, with a version tag.

### Phase
The build order: 1=foundation, 2=storage, 3=control, 4=ops. Not a runtime concept.

### POSIX
The file-system semantics this repo's `dfs-mds` simulates: hierarchical namespace, inodes, capabilities for close-to-open consistency.

### PriorityClass
`dfs-custodian.PriorityClass` enum: CRITICAL_REPAIR > ROUTINE_REPAIR > DEEP_SCRUB > SHALLOW_SCRUB > REBALANCE > TIER_TRANSITION.

### Prometheus exporter
`dfs-metrics.PrometheusExporter.expose()` — plaintext exposition format for the `Counter`, `Gauge`, `Histogram` primitives.

### QosClass
`dfs-qos.QosClass(name, reservation, weight, limit)` — the three-knob multi-tenant scheduler parameters.

### Range
`dfs-allocator.Range(start, length)` — a contiguous run of allocation units.

### Range scan
Not implemented in this repo.

### Recall (capability)
`dfs-mds.MdsCluster.recall(path)` — the MDS revoking a cap from a client when a conflicting access arrives.

### Reed-Solomon (RS)
EC family. `dfs-erasure.ReedSolomon` — XOR-stub implementation.

### Replica
A copy of a chunk pinned to an OSD. `dfs-common.ReplicaId(ChunkId, OsdId)`.

### Replication
N-way replication. `dfs-erasure.Replication`. Storage cost N×, repair-read 1×.

### Reservation (dmClock tag)
The guaranteed minimum IOPS for a class.

### Sandbox
Not used in this repo.

### Scrub
Background data-integrity check. Two variants: shallow (metadata only) and deep (block content). Mapped to `PriorityClass.SHALLOW_SCRUB` / `DEEP_SCRUB`.

### Sealed extent
An extent in `ExtentStatus.SEALED`. No further appends accepted.

### Shard (MDS)
A partition of the namespace owned by one MDS node. `dfs-mds.SubtreePartitioner.assign(subtree, mdsId)`.

### StrawSelector
The hashed-weight selection algorithm at the heart of CRUSH. `dfs-crush.StrawSelector`. Adding a bucket only steals from existing buckets in proportion to weight.

### Subtree partitioning
See **dynamic subtree partitioning**.

### TenantId
`dfs-common.TenantId(String)` — tenant boundary identifier. Used by `dfs-security.Kms.generateDuk(tenant, obj)`.

### Topology
The hierarchy in the CRUSH map: root → row → rack → host → osd. Encoded in `dfs-crush.CrushBucket`.

### Tier transition
Background re-encoding from 3× replication to LRC, or LRC to deeper LRC. Modeled as `PriorityClass.TIER_TRANSITION`.

### Virtual time (dmClock)
Per-class tag that advances by `1/rate` on each submission. The scheduler picks the class with the smallest tag.

### WAL (Write-Ahead Log)
In `dfs-storage.Osd`, the in-memory queue that absorbs writes below the small-write threshold (default 4 KB). Background flushed into aligned extents via `flushDeferred`.

### Watchdog
Not used in this repo.

### Weight (dmClock tag)
A class's proportional share of capacity once reservations are satisfied.

### WorkItem
`dfs-custodian.WorkItem(PgId, PriorityClass, reason)` — one unit of background work emitted by the scanner and dispatched by the Custodian.
