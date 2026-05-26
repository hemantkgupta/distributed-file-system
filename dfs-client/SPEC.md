# dfs-client — LLM Implementation Spec

> **Status:** SPEC. No code yet. Generate against this; tick off the checklist in §11 as code lands.
>
> **Maps to:** §6 Client Library (Thick Client) in the [full essay](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system-full.md#6-client-library-thick-client) and the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#6-client-library-thick-client).
>
> **Out of scope for this implementation pass:** real RPC fabric (in-process composition only — see §9), kernel FUSE mount, mmap-style page-cache eviction policies beyond LRU, multi-tenant cap-cache partitioning, encryption-at-rest hooks, the C/POSIX shim (`libdfs.so`). These are called out per-section below.

---

## 1. Purpose

Expose the cluster to in-process Java applications and to the §7 / §8 gateways as a thick client: cap-aware, map-aware, write-buffered, page-cached, and scatter-gather-routed straight at the OSDs. The library is the only entry point that touches the MDS, block layer, and OSDs in one cohesive session.

The architecture commits to a **thick client** because the per-request path to NVMe-class OSDs cannot afford a proxy hop. The library holds capability vectors so the common path never makes a synchronous MDS RPC, caches the cluster map so PG → OSD resolution is in-memory, batches writes so small POSIX appends become large network frames, and dispatches read / write fanout to OSD primaries in parallel.

The library is **stateful per-process** but **stateless across processes** — every JVM that links the library opens its own MDS session and warms its own caches. There is no shared client daemon.

---

## 2. Position in the system

- **Upstream consumers:**
  - §7 Object Gateway (`dfs-gateway-s3`) — translates S3 verbs into client-library calls.
  - §8 POSIX Gateway (`dfs-gateway-posix`, not yet built) — same role, NFS/SMB/CSI front-end.
  - Any in-process Java application that links the library directly.
- **Downstream dependencies:**
  - **`dfs-mds`** for namespace ops and capability grant / recall.
  - **`dfs-placement`** for object → PG → OSD resolution.
  - **`dfs-lease`** for chunk-lease holds during writes.
  - **`dfs-monitor`** for cluster-map subscription (epoch bumps, OSD up/down).
  - **`dfs-storage`** for the read / write byte path.
  - **`dfs-erasure`** for encode / decode on EC-coded pools.
  - **`dfs-common`** for shared value types (`ObjectId`, `PgId`, `OsdId`, `ChunkId`).
- **Sibling coordination:**
  - The Custodian (§5) does not touch the client library directly. It mutates the cluster map; the library hears about it via the monitor subscription.

---

## 3. Public API surface

### 3.1 The `ClientLibrary` interface

This interface **subsumes** the mock contract defined in `dfs-gateway-s3/SPEC.md` §10 — every mock method is preserved with the same signature so existing §7 callers compile against the real library unchanged. The thick-client extensions sit alongside.

```java
package com.hkg.dfs.client;

import java.util.List;
import java.util.function.Consumer;

public interface ClientLibrary extends AutoCloseable {

    // ---- File ops (subsumes the §7 mock) ------------------------------------
    long    create  (String path);                                  // returns inode id
    long    open    (String path);                                  // O_RDWR; throws if not found
    void    write   (long inode, long offset, byte[] bytes);        // async — lands in WriteBuffer
    int     read    (long inode, long offset, byte[] buf);          // returns bytes read
    long    size    (long inode);
    void    close   (long inode);                                   // implicit fsync + cap_release
    void    unlink  (String path);

    // ---- Directory ops (subsumes the §7 mock) ------------------------------
    void          mkdir   (String path);
    List<String>  readdir (String path);
    boolean       exists  (String path);

    // ---- Thick-client extensions -------------------------------------------
    /** Cap-aware open. Caller declares the cap modes it expects (read / write / file-shared / lock). */
    long          open    (String path, CapMode... modes);

    /** Block until all dirty bytes for {@code inode} are acked by OSD primaries. */
    void          fsync   (long inode);

    /** Block until every dirty buffer in the library is flushed. Used on graceful shutdown. */
    void          flush   ();

    /** Subscribe to cluster-map epoch bumps. Callback fires on every new epoch the library learns. */
    Subscription  onMapEpoch (Consumer<MapEpoch> callback);

    /** Returns a snapshot of the four caches — for tests and ops dashboards. */
    ClientStats   stats   ();

    @Override
    void close();                                                   // shuts down the session
}
```

### 3.2 Cap modes

```java
package com.hkg.dfs.client;

public enum CapMode {
    READ,            // may cache bytes, may issue OSD reads
    WRITE,           // may dirty the write buffer, must flush on recall
    FILE_SHARED,     // may share with peer clients; loses cache-coherence guarantee
    LOCK             // exclusive — no peer client may hold any cap concurrently
}
```

### 3.3 Subscription handle

```java
package com.hkg.dfs.client;

public interface Subscription extends AutoCloseable {
    @Override void close();
}

public record MapEpoch(long epoch, long observedAtNanos) {}
```

### 3.4 Session entry point

```java
package com.hkg.dfs.client;

public final class ClientSession {
    public static ClientLibrary open(ClientConfig cfg);
}

public record ClientConfig(
    String  clusterId,
    int     writeBufferBytes,        // default: 64 MiB
    int     pageCacheBytes,          // default: 256 MiB
    long    capTtlMillis,            // default: 60_000
    long    flushTimerMillis,        // default: 5_000
    int     scatterGatherParallelism // default: 8
) {}
```

---

## 4. Data model

### 4.1 Capability cache (`CapCache`)

```java
package com.hkg.dfs.client;

import java.util.EnumSet;

public final class CapCache {
    public record Entry(
        long          inodeId,
        EnumSet<CapMode> modes,
        long          grantedAtNanos,
        long          expiresAtNanos,
        long          recallDeadlineNanos    // 0 if not under recall
    ) {}

    public Entry  get      (long inodeId);                    // null on miss
    public void   put      (Entry e);
    public void   recall   (long inodeId, long deadlineNanos);// MDS asked us to drop this cap
    public void   release  (long inodeId);                    // we voluntarily release
    public int    size     ();
}
```

### 4.2 Cluster-map cache (`ClusterMapCache`)

```java
package com.hkg.dfs.client;

import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;

import java.util.List;
import java.util.Optional;

public final class ClusterMapCache {
    public record PgEntry(PgId pg, List<OsdId> osds, long mapEpoch) {}

    public long              currentEpoch();
    public Optional<PgEntry> lookup       (PgId pg);          // null on miss
    public void              put          (PgEntry e);
    public void              invalidate   (PgId pg);          // on OSD redirect
    public void              bumpEpoch    (long newEpoch);    // from monitor subscribe
}
```

### 4.3 Write buffer (`WriteBuffer`)

```java
package com.hkg.dfs.client;

import java.util.List;

public final class WriteBuffer {
    public record DirtyRange(long inodeId, long offset, byte[] bytes, long enqueuedAtNanos) {}

    public void               append      (long inodeId, long offset, byte[] bytes);
    public List<DirtyRange>   drain       (long inodeId);     // flush one file
    public List<DirtyRange>   drainAll    ();                 // flush all
    public long               dirtyBytes  ();
    public long               dirtyBytes  (long inodeId);
    public boolean            isFull      ();                 // ≥ writeBufferBytes
}
```

### 4.4 Page cache (`PageCache`)

```java
package com.hkg.dfs.client;

import java.util.Optional;

public final class PageCache {
    public record Page(long inodeId, long offset, byte[] bytes, long mapEpochAtFill) {}

    public Optional<Page> get         (long inodeId, long offset);
    public void           put         (Page p);
    public void           invalidate  (long inodeId);           // on cap recall
    public long           bytesHeld   ();
}
```

### 4.5 Scatter-gather coordinator

```java
package com.hkg.dfs.client;

import java.util.List;

public final class ScatterGather {
    public record ChunkSlice(long inodeId, long offset, byte[] bytes, com.hkg.dfs.common.PgId pg) {}

    /** Split a logical write into (PG → bytes) slices using the file_layout from the cap. */
    public List<ChunkSlice> slice   (long inodeId, long offset, byte[] bytes, FileLayout layout);

    /** Dispatch slices to OSD primaries in parallel; block until all ack. */
    public void             dispatchWrites (List<ChunkSlice> slices);

    /** Symmetric for reads: split a read range into PG-scoped sub-reads, assemble in offset order. */
    public byte[]           dispatchReads  (long inodeId, long offset, int length, FileLayout layout);
}

public record FileLayout(long chunkSizeBytes, int replicationFactor) {}
```

### 4.6 Client stats (introspection)

```java
package com.hkg.dfs.client;

public record ClientStats(
    int  capCacheSize,
    long mapCacheEpoch,
    long writeBufferDirtyBytes,
    long pageCacheBytesHeld,
    long capRecallsObserved,
    long mapEpochBumpsObserved
) {}
```

---

## 5. Life of a request

### 5.1 `read` (cap-cache hit, page-cache miss)

```
App → ClientLibrary.read → PageCache → ScatterGather → OSDs
```

1. Application calls `read(inode, offset, buf)`.
2. Library checks `CapCache.get(inode)`. Hit with `READ` mode and `expiresAt` in the future → proceed without MDS RPC.
3. Library checks `PageCache.get(inode, offset)`. Miss.
4. Library asks the cap entry for the `FileLayout` (chunk size, replication). For each chunk in `[offset, offset+len)`:
   a. Hash `(inodeId, chunkOffset)` to `PgId` (mirrors `dfs-placement.objectToPg`).
   b. `ClusterMapCache.lookup(pg)`. Hit → use cached OSD list; miss → block-layer RPC, then `put` into cache.
5. `ScatterGather.dispatchReads` fans the per-PG reads out to OSD primaries in parallel (capped at `scatterGatherParallelism`).
6. OSDs return bytes; library verifies CRC32c against the file_layout's checksums.
7. Library assembles into `buf` in offset order, calls `PageCache.put` for each chunk just read, returns to the app.
8. If any OSD returns a redirect (stale map): library calls `ClusterMapCache.invalidate(pg)`, refetches that PG's entry from block layer, retries that slice.

### 5.2 `read` (cap-cache miss)

1. Library does not hold a cap for `inode`. RPC MDS `open(inodeId, O_RDONLY)`.
2. MDS returns `(readCap, fileLayout, expiresAt)`. Library calls `CapCache.put(entry)`.
3. Proceeds from step 3 of §5.1.

### 5.3 `write` + `fsync` (small writes coalesce in the buffer)

```
App → write → WriteBuffer.append → return immediately
              ↓ (later, on fsync / fill / recall / timer)
              ScatterGather → OSD primaries
```

1. Application calls `write(inode, offset, bytes)`. Library checks `CapCache` for a `WRITE` cap on `inode`.
   - Hit → proceed.
   - Miss → RPC MDS `open(inode, O_RDWR)`, receive `writeCap + fileLayout`, `CapCache.put`.
2. Library calls `WriteBuffer.append(inode, offset, bytes)`. Returns to the app immediately. **No bytes have left the host.**
3. If `WriteBuffer.isFull()` after the append, the library triggers an async flush (see step 5). The append does not block on it unless the buffer is fully saturated past `writeBufferBytes` — in which case `write` blocks (backpressure).
4. The flush timer fires every `flushTimerMillis` and drains buffers older than the timer interval.
5. **Flush path** (triggered by fsync, fill, recall, or timer):
   a. `WriteBuffer.drain(inode)` returns the list of dirty ranges for the file.
   b. For each range, `ScatterGather.slice` partitions the bytes by PG using the cap's `FileLayout`.
   c. `ScatterGather.dispatchWrites` sends each slice to its PG's primary OSD in parallel (the primary handles forward-to-secondaries per §4 of the essay).
   d. Library awaits acks from every primary. Any failure → propagate to the caller (for `fsync`) or retry (for the timer path).
6. On `fsync(inode)`: library executes the flush path synchronously and only returns once every dirty range for `inode` is acked.
7. On `close(inode)`: implicit `fsync`; then `CapCache.release(inode)` + MDS `cap_release` RPC.
8. On a cap recall mid-write (see §6): the library prioritizes flush-on-recall over normal-priority writes; if it cannot complete the flush before `recallDeadlineNanos`, it returns an error to the application and drops the unflushed bytes.

### 5.4 Cluster-map epoch bump (subscription callback)

1. `dfs-monitor` publishes a new epoch.
2. The library's `onMapEpoch` callback fires. The library calls `ClusterMapCache.bumpEpoch(newEpoch)`.
3. The library does **not** eagerly invalidate every PG — that would cause a storm. Stale entries get caught lazily on the next OSD redirect.

---

## 6. Invariants the implementation must hold

After a successful `write(inode, off, bytes)` followed by `fsync(inode)`:
- Every byte is durable: every OSD primary covering that range has acked. A peer client with a fresh cap will see exactly those bytes at `read(inode, off, len)`.
- The library has zero dirty bytes for `inode` in `WriteBuffer`.

After a successful `close(inode)`:
- Implicit `fsync` ran first; therefore every byte ever `write`-n through this fd is durable.
- The library holds no cap for `inode`. A subsequent `read` on this library instance is a cold-start (cap-cache miss, then page-cache miss).

**Cap-recall contract.** When the MDS issues `recall(inodeId, deadlineNanos)`:
- The library has **finite time** (until `deadlineNanos`, typically 60 s) to flush every dirty range for `inode` in `WriteBuffer`.
- The library must prioritize flush-on-recall over normal-priority writes.
- If the flush completes before `deadlineNanos`: library issues `cap_release` to the MDS; `CapCache.release(inode)`; `PageCache.invalidate(inode)`.
- If the flush does **not** complete by `deadlineNanos`: library drops the unflushed bytes, surfaces an `IOException` on the next app call against that fd, and accepts the cap revocation. **No partial data may leak into the cluster after `deadlineNanos`.**

**Cluster-map staleness contract.** If an OSD returns a redirect on a read or write:
- Library invalidates the offending PG entry in `ClusterMapCache`.
- Library refetches the PG from the block layer and retries the slice.
- Library never serves stale-map bytes back to the application — the redirect always triggers a refresh before returning to the caller.

The library must **never silently lose dirty bytes** outside the cap-recall-deadline-exceeded path. Every other write failure routes to an `IOException` propagated to the caller.

---

## 7. Failure modes & required handling

| Trigger | Surface | Library action |
|---|---|---|
| MDS RPC fails on `open` | `IOException` to caller | exponential backoff, retry up to N times; surface failure after N |
| Cap-cache hit but cap expired (TTL elapsed) | n/a (internal) | treat as miss; re-RPC MDS |
| Cap recall — flush completes in time | n/a | normal flush + `cap_release` |
| Cap recall — flush exceeds deadline | `IOException` on next fd op | drop dirty bytes; cap is gone |
| OSD redirect on read | n/a | invalidate PG, refetch from block layer, retry |
| OSD redirect on write (post-buffer) | n/a | same; the buffered bytes are still in the library, retry against the new primary |
| Write buffer full and app keeps writing | `write` blocks | backpressure — no data loss |
| Map subscription channel drops | logged warning | reconnect with jittered backoff; on reconnect refetch current epoch and bump cache |
| MDS shard authority migrates mid-op | n/a | follow redirect; the MDS shards run two-phase commit so no double-apply |
| Block-layer RPC fails | `IOException` propagated | no point retrying — operator-visible failure |
| Library shutdown (`close()`) with dirty buffer | blocks until flushed | `flush()` is called first; if any flush fails, `close()` surfaces the failure |

**Note on retry storms.** A cluster-wide topology change can cause many clients to refetch simultaneously. The library uses jittered backoff on every block-layer RPC, and prefers diff-based subscription updates from the monitor over full-map refreshes.

---

## 8. Testing acceptance criteria

Required tests in `dfs-client/src/test/java/com/hkg/dfs/client/`. Use the real `dfs-mds`, `dfs-placement`, `dfs-storage` modules in in-process composition (see §9). Tests should not touch disk except through the storage daemon's existing temp-dir convention.

| Test class | Test method | Asserts |
|---|---|---|
| `ClientLibraryReadWriteTest` | `roundTripSmallWrite` | write 4 KiB at offset 0; fsync; read it back; bytes identical |
| `ClientLibraryReadWriteTest` | `roundTripLargeWrite` | write 8 MiB spanning multiple PGs; fsync; read; bytes identical |
| `ClientLibraryReadWriteTest` | `readAfterWriteWithoutFsyncIsVisible` | write then read on same fd before fsync → returns the buffered bytes (read-your-writes for the same library) |
| `ClientLibraryReadWriteTest` | `sparseWritesCoalesceCorrectly` | write at offset 0 then offset 1 MiB; read [0, 1 MiB + 4 KiB) → returns the two writes with zeros between |
| `CapCacheTest` | `openWithCachedCapSkipsMdsRpc` | second `open` of same path increments MDS call count by 0 |
| `CapCacheTest` | `capExpiryTriggersReFetch` | force TTL to 0; next op re-RPCs MDS |
| `CapCacheTest` | `recallWithinDeadlineFlushesCleanly` | recall fires; dirty bytes flush; subsequent reader sees them |
| `CapCacheTest` | `recallExceedingDeadlineSurfacesIoException` | recall deadline = 1 ms while flush is artificially slow → next fd op throws IOException |
| `ClusterMapCacheTest` | `mapEpochBumpDoesNotInvalidateEagerly` | bump epoch; subsequent read hits cached PG entry; no block-layer RPC |
| `ClusterMapCacheTest` | `osdRedirectInvalidatesPg` | OSD returns redirect; next read re-RPCs the block layer for that PG |
| `WriteBufferTest` | `appendCoalescesContiguousRanges` | three consecutive `write`s of 4 KiB at adjacent offsets → drain returns one logical range (or three; assert byte content matches) |
| `WriteBufferTest` | `fullBufferTriggersBackpressure` | write past `writeBufferBytes` → call blocks until buffer drains |
| `WriteBufferTest` | `flushTimerDrainsStaleRanges` | write 4 KiB; wait > `flushTimerMillis`; assert buffer is empty without explicit fsync |
| `PageCacheTest` | `readHitsPageCacheOnSecondCall` | read twice at same offset; second read does zero OSD RPCs |
| `PageCacheTest` | `capRecallInvalidatesPages` | recall fires; subsequent read re-fetches from OSDs |
| `ScatterGatherTest` | `writeAcross3PgsDispatchesInParallel` | write spanning 3 PGs → 3 OSD primaries see one write each; total wall-clock ≈ slowest primary's RTT (not the sum) |
| `ScatterGatherTest` | `partialOsdFailureSurfacesError` | one PG's primary unreachable → fsync throws; other PGs are left in a flushed state (no atomic rollback — documented limitation) |
| `MapSubscriptionTest` | `epochBumpDeliveredToCallback` | monitor publishes new epoch → callback fires with the new value |

All tests pass under `./gradlew :dfs-client:test`.

---

## 9. Stubs allowed / out of scope (initial pass)

- **Real RPC fabric.** The initial implementation runs as **in-process composition** of `dfs-mds`, `dfs-placement`, `dfs-storage`, `dfs-monitor`, `dfs-lease`, `dfs-erasure`. The library calls those modules directly via their Java APIs — there is no network hop. A future pass introduces a network transport (`dfs-rpc`) that the library can talk over without changing this interface.
- **C / POSIX shim.** The essay calls out a C-callable `libdfs.so` exposing `open` / `read` / `write` / `fsync`. That is out of scope for the first pass — Java callers only.
- **FUSE mount.** Not built in this pass; the §8 POSIX gateway will own kernel-side protocol heads when it exists.
- **mmap-style page-cache eviction.** First-pass `PageCache` is plain LRU with byte budget. No huge-page / NUMA-aware variants.
- **Multi-tenant cap-cache partitioning.** Cap entries are global per JVM. Multi-tenant isolation is the gateway's job above the library, not the library's.
- **Encryption-at-rest hooks.** The library does not interpose on byte payloads; encryption is handled by the storage daemon (when `dfs-security` ships KMS integration).
- **Cluster-map diff transport.** First-pass map subscription delivers full epochs only. Diff-based transport is a follow-up.
- **Cross-PG atomic writes.** A `write` that spans multiple PGs is **not** atomic across PGs — partial flush failure leaves some PGs flushed and some dirty. This is documented in §7 and `ScatterGatherTest.partialOsdFailureSurfacesError`. Real cross-PG atomicity needs a two-phase commit layer that's out of scope.

---

## 10. Dependencies

### Build

```groovy
// In root build.gradle, add:
project(':dfs-client') {
    dependencies {
        api project(':dfs-common')
        implementation project(':dfs-mds')
        implementation project(':dfs-placement')
        implementation project(':dfs-lease')
        implementation project(':dfs-monitor')
        implementation project(':dfs-storage')
        implementation project(':dfs-erasure')
        implementation project(':dfs-node')         // see "Relationship to dfs-node" below
    }
}
```

### Runtime

JDK 17+. No external runtime dependencies — pure Java composing the existing modules.

### Relationship to `dfs-node`

`dfs-node` already wires `dfs-crush` + `dfs-placement` + `dfs-lease` + `dfs-storage` into a single `NodeApi.put(obj, bytes)` entry point. **Recommendation: `dfs-client` depends on `dfs-node`, it does not replace it.** Reasoning: `dfs-node` is a deliberately minimal write-only composition used by the simulator; evolving it into the thick client would break the simulator's expectations. `dfs-client` reuses `dfs-node`'s composition for the underlying byte-write sequence and layers the four caches, the read path, the cap lifecycle, and map subscription on top.

### Wiki concepts implemented

- [`capability-vector`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/capability-vector.md)

### Essay section

[§6. Client Library (Thick Client)](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system-full.md#6-client-library-thick-client)

---

## 11. Implementation checklist

Roughly in order. Each item should land with its tests.

**Foundations**
- [ ] `ClientConfig` record with defaults
- [ ] `CapMode` enum
- [ ] `MapEpoch`, `FileLayout`, `ClientStats` records
- [ ] `Subscription` interface
- [ ] `ClientLibrary` interface (subsumes the §7 mock contract; the gateway compiles against it unchanged)
- [ ] `ClientSession.open(ClientConfig)` factory returning a `ClientLibrary` instance

**Cache 1 — Capability cache**
- [ ] `CapCache` class with `Entry`, `get`, `put`, `recall`, `release`, `size`
- [ ] TTL expiry handling
- [ ] Recall deadline tracking
- [ ] `CapCacheTest.*` (four tests)

**Cache 2 — Cluster-map cache**
- [ ] `ClusterMapCache` class with `PgEntry`, `lookup`, `put`, `invalidate`, `bumpEpoch`, `currentEpoch`
- [ ] Subscription wire-up to `dfs-monitor`
- [ ] `ClusterMapCacheTest.*` (two tests) + `MapSubscriptionTest.epochBumpDeliveredToCallback`

**Cache 3 — Write buffer**
- [ ] `WriteBuffer` class with `DirtyRange`, `append`, `drain`, `drainAll`, `dirtyBytes`, `isFull`
- [ ] Background flush timer thread
- [ ] Backpressure semantics (block `write` when past budget)
- [ ] `WriteBufferTest.*` (three tests)

**Cache 4 — Page cache**
- [ ] `PageCache` class with `Page`, `get`, `put`, `invalidate`, `bytesHeld`
- [ ] LRU eviction by byte budget
- [ ] Invalidation on cap recall
- [ ] `PageCacheTest.*` (two tests)

**Scatter-gather coordinator**
- [ ] `ScatterGather` class with `ChunkSlice`, `slice`, `dispatchWrites`, `dispatchReads`
- [ ] PG-fanout via `dfs-placement.objectToPg`
- [ ] Parallel dispatch capped at `scatterGatherParallelism`
- [ ] CRC32c verification on read path
- [ ] `ScatterGatherTest.*` (two tests)

**Top-level library glue**
- [ ] `ClientLibraryImpl` implementing `ClientLibrary`, composing the four caches + scatter-gather
- [ ] Cap-recall wiring (MDS → CapCache → WriteBuffer flush + PageCache invalidate)
- [ ] `onMapEpoch` callback wiring through to `dfs-monitor`
- [ ] `stats()` returning a fresh `ClientStats`
- [ ] `ClientLibraryReadWriteTest.*` (four tests)

**Wiring**
- [ ] Add `':dfs-client'` to `settings.gradle`
- [ ] Add dependency block to root `build.gradle` (see §10)
- [ ] All tests pass under `./gradlew :dfs-client:test`
- [ ] `dfs-gateway-s3` swapped from its in-test `InMemoryClientLibrary` to `ClientSession.open(...)` — gateway tests still pass

**Documentation**
- [ ] Write `docs/modules/dfs-client.md` in the existing 7-section format (Role → Wiki anchor → Public API surface → Internal structure → Key tests → Where it fits → Stubs and departures from production)
- [ ] Add `dfs-client` row to `docs/modules/README.md` index
- [ ] Update [`wiki/my-explanations/distributed-file-system-services.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md) §6 status from ⚠️ Partial → ✅ Implemented

When all boxes are ticked, this SPEC.md can be moved to `docs/specs/dfs-client.md` for historical reference. The `docs/modules/dfs-client.md` doc becomes the contract going forward.
