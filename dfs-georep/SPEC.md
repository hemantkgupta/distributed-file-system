# dfs-georep — LLM Implementation Spec

> **Status:** SPEC. No code yet. Generate against this; tick off the checklist in §11 as code lands.
>
> **Maps to:** §9 Geo-Replication Service in the [full essay](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system/distributed-file-system-full.md#9-geo-replication-service-async-log-shipper) and the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#9-geo-replication-service-async-log-shipper).
>
> **Out of scope for this implementation pass:** real WAN wire protocol (TCP/QUIC framing, congestion control), cross-region mTLS / IAM, adaptive snapshot scheduling, bidirectional replication topologies, conflict resolution for multi-master writes, partial-extent shipping. The WAN channel is a loopback simulator — see §9. These are called out per-section below.

---

## 1. Purpose

Ship data and metadata changes from one region to another **asynchronously**. The architecture commits to async geo-replication by default: synchronous cross-region replication on every write is unacceptable on a latency budget at WAN distances, and only the few namespaces that genuinely need it pay for it.

The unit of replication is the **sealed extent** (for chunk data) and the **journal record** (for metadata). Sealed extents are immutable post-seal — the same primitive that solves write-path consistency makes async shipping race-free. The source's "shipped" cursor advances only when the applier on the target side acks, and the applier records every applied extent so a duplicate apply (after a crash) is a safe no-op.

Each region runs **two roles**:
- **Shipper** — source-side. Watches for newly sealed extents and new journal records, batches them into snapshots, streams them across the WAN channel to the peer region's applier.
- **Applier** — target-side. Receives extents and journal records, writes them into the local cluster, records applied-extent entries for idempotent replay.

A bidirectional setup runs both roles on each side; this spec covers a single direction (source → target) and assumes the inverse direction is symmetric.

---

## 2. Position in the system

- **Upstream consumers:** the geo-replication service has no API consumers in the conventional sense — it is a background daemon driven by sealed-extent and journal-record events. Operators consume its RPO metrics and trigger failover via an admin RPC.
- **Downstream dependencies:**
  - **`dfs-mds`** — source-side journal records; target-side journal apply.
  - **`dfs-storage`** — source-side sealed extents (read bytes by extent ID); target-side write-and-finalize.
  - **`dfs-lease`** — sealed-extent metadata (`Extent`, `ExtentStatus.SEALED`, `sealedLength`).
  - **`dfs-common`** — shared value types.
- **Sibling coordination:**
  - The Custodian (§5) reclaims storage for snapshots once both sides agree they are durably shipped. This service writes the `shipped_at` timestamp on the manifest; the Custodian reads it.
  - The Cluster Monitor (§3) participates in failover declarations — this service exposes a `triggerFailoverReplay()` admin RPC that the monitor invokes.

---

## 3. Public API surface

### 3.1 Shipper interface (source-side)

```java
package com.hkg.dfs.georep;

/** Source-side role. Watches sealed extents and journal records, ships them to a peer region. */
public interface Shipper {
    /** Start the background ship loop. Idempotent. */
    void start();

    /** Stop the background ship loop and flush in-flight work. */
    void stop();

    /** Called by dfs-lease when an extent transitions OPEN -> SEALED. Enqueues for shipping. */
    void onExtentSealed(String extentId);

    /** Called by dfs-mds when a journal record is durably appended. Enqueues for shipping. */
    void onJournalRecord(JournalRecord record);

    /** Close the current snapshot, open a new one, return the closed snapshot's id. */
    UUID rollSnapshot();

    /** Current RPO lag in seconds — wall-clock age of the oldest unshipped record. */
    long rpoLagSeconds();

    /** Snapshot the shipper's progress for observability and reconciliation. */
    ShipperState state();
}

public record ShipperState(
    UUID    currentSnapshotId,
    UUID    lastShippedSnapshotId,
    long    extentsQueued,
    long    extentsShipped,
    long    journalRecordsQueued,
    long    journalRecordsShipped,
    Instant oldestUnshippedAt
) {}
```

### 3.2 Applier interface (target-side)

```java
package com.hkg.dfs.georep;

/** Target-side role. Receives extents and journal records from a peer Shipper, writes them locally. */
public interface Applier {
    /** Start the background apply loop. Idempotent. */
    void start();

    /** Stop the background apply loop and flush in-flight work. */
    void stop();

    /** Called by the WAN channel inbound handler. Returns the ack the source observes. */
    Ack shipExtent(String extentId, byte[] bytes, UUID manifestRef);

    /** Called by the WAN channel inbound handler. Returns the ack the source observes. */
    Ack shipJournalRecord(JournalRecord record);

    /** Diff source vs. target view of a snapshot; used for periodic reconciliation. */
    ExtentStateDiff reconcileSnapshot(String namespace, UUID snapshotId);

    /** Has this extent already been applied? Idempotency probe. */
    boolean isApplied(String extentId);

    /** Replay all manifests whose shipped_at is set but whose extents are not yet visible on target. Used on failover promotion. */
    int triggerFailoverReplay(String namespace);

    /** Snapshot the applier's progress for observability. */
    ApplierState state();
}

public record Ack(String extentId, boolean accepted, String reason) {
    public static Ack ok(String extentId)            { return new Ack(extentId, true,  ""); }
    public static Ack duplicate(String extentId)     { return new Ack(extentId, true,  "duplicate"); }
    public static Ack rejected(String extentId, String why) { return new Ack(extentId, false, why); }
}

public record ApplierState(
    long extentsApplied,
    long duplicateExtents,
    long journalRecordsApplied,
    Instant lastAppliedAt
) {}

public record ExtentStateDiff(
    UUID         snapshotId,
    Set<String>  missingOnTarget,    // shipper sent, target never recorded as applied
    Set<String>  extraOnTarget       // applied without a matching source manifest entry
) {}
```

### 3.3 WAN channel interface (transport)

```java
package com.hkg.dfs.georep;

/**
 * Pluggable transport between Shipper and Applier. For this implementation pass the only
 * concrete implementation is {@link LoopbackChannel} — an in-JVM simulator with configurable
 * latency and packet loss. A real TCP/QUIC channel is future work.
 */
public interface WanChannel {
    Ack sendExtent (String extentId, byte[] bytes, UUID manifestRef);
    Ack sendJournalRecord(JournalRecord record);
    /** Attach the applier that incoming traffic should be delivered to. */
    void bind(Applier applier);
}

public final class LoopbackChannel implements WanChannel {
    public LoopbackChannel(Duration simulatedLatency, double dropProbability);
    // ... bind, sendExtent, sendJournalRecord
}
```

---

## 4. Data model

### 4.1 Snapshot manifest record

The schema mirrors the essay's §9 verbatim. Lives in the source region's KV (the source-side MDS in this pass — see §9 stubs).

```java
public record SnapshotManifestRecord(
    UUID         snapshotId,
    String       namespace,
    UUID         parentSnapshot,        // null for the first snapshot in a namespace
    List<ExtentRef> extents,
    JournalWindow   journalWindow,
    Instant      createdAt,
    Instant      shippedAt              // null until applier has acked every extent
) {}

public record ExtentRef(
    String extentId,
    long   sourceOsd,
    long   length,
    String checksum                     // hex sha-256 of the extent body
) {}

public record JournalWindow(
    long startLsn,
    long endLsn
) {}
```

KV key: `snapshot:{namespace}:{snapshotId}` (string form).

### 4.2 Applied-extent record

Lives in the **target region's** KV, keyed by extent ID. It is the idempotency cornerstone: a duplicate ship is detected here and dropped with `Ack.duplicate(...)`.

```java
public record AppliedExtentRecord(
    String  extentId,
    Instant appliedAt,
    String  sourceRegion,
    long    targetOsd
) {}
```

KV key: `applied:{namespace}:{extentId}`.

### 4.3 Journal record (metadata changes)

A typed value object representing a single MDS journal entry that must be replayed on the target.

```java
public record JournalRecord(
    long   lsn,
    String namespace,
    JournalOp op,
    byte[] payload,                     // op-specific serialised body
    Instant emittedAt
) {}

public enum JournalOp {
    CREATE_INODE,
    UNLINK_INODE,
    RENAME,
    UPDATE_ATTR,
    GRANT_CAP,
    REVOKE_CAP,
    SEAL_EXTENT                          // metadata-only; the bytes ship through ExtentRef
}
```

### 4.4 Configuration

```java
public record GeoRepConfig(
    String  sourceRegion,
    String  targetRegion,
    Duration snapshotInterval,           // default: 5s — periodic; not adaptive
    int      shipperWorkerThreads,       // default: 4
    int      maxInFlightExtents,         // default: 16
    Duration ackTimeout                  // default: 30s
) {}
```

---

## 5. Life of a request

### 5.1 Ship a sealed extent end-to-end

```
dfs-lease (seal)
  → Shipper.onExtentSealed
    → Shipper batches into current SnapshotManifestRecord
      → WanChannel.sendExtent
        → Applier.shipExtent
          → AppliedExtentRecord written, OSD write, Ack
            → Shipper marks ExtentRef shipped, advances cursor
              → Snapshot manifest's shippedAt set when all extents acked
```

1. An OSD in the source region transitions an extent `OPEN → SEALED` via `dfs-lease`'s `ExtentService.seal(...)`. The `Extent` record now carries `sealedLength`.
2. The `dfs-lease` integration layer notifies the local `Shipper` via `onExtentSealed(extentId)`.
3. The Shipper looks up the `Extent` metadata, reads the sealed bytes from the source OSD (`dfs-storage`), computes the SHA-256 checksum, and appends an `ExtentRef(extentId, sourceOsd, length, checksum)` to the **current** `SnapshotManifestRecord` (the one with no `shippedAt` yet).
4. A shipper worker thread dequeues the `ExtentRef`, reads the bytes again from `dfs-storage` (or from a small LRU cache), and invokes `wanChannel.sendExtent(extentId, bytes, currentSnapshotId)`.
5. The `LoopbackChannel` sleeps for the configured simulated latency, applies the configured drop probability (on drop, returns `Ack.rejected(extentId, "wan_drop")` — the shipper retries with backoff), then dispatches to the bound `Applier.shipExtent(...)`.
6. The Applier checks `isApplied(extentId)` first. If true → returns `Ack.duplicate(extentId)` immediately without writing. Otherwise:
   a. Picks a target OSD via the target region's local placement.
   b. Writes the bytes through `dfs-storage` (target-side).
   c. Writes the `AppliedExtentRecord` to the target-region KV.
   d. Returns `Ack.ok(extentId)`.
7. The Shipper observes the ack. On `ok` or `duplicate` it marks the `ExtentRef` shipped in its in-memory snapshot state. On `rejected` it requeues with exponential backoff.
8. When **every** `ExtentRef` in a `SnapshotManifestRecord` is shipped (and every journal record in its `journalWindow` is acked), the Shipper sets the manifest's `shippedAt = now()`. This advances the cluster-level RPO marker.
9. On the periodic `snapshotInterval` (default 5s), `rollSnapshot()` closes the current snapshot, opens a new one with `parentSnapshot = closedId`, and the loop continues.

### 5.2 Recover from applier crash mid-apply

The hard case is when the Applier writes the bytes and the `AppliedExtentRecord`, but crashes before sending the ack. The Shipper times out, retries the same `(extentId, bytes)` to a freshly-restarted Applier.

1. Shipper sends extent `E` to Applier; the Applier writes the bytes and the `AppliedExtentRecord(E, ...)`, then crashes before the ack frame goes out on the channel.
2. The Shipper's `ackTimeout` fires (default 30s). The Shipper requeues the extent with `attemptCount += 1`.
3. The replacement Applier process starts. Its KV view is durable, so the `AppliedExtentRecord` for `E` is still present.
4. The Shipper retries: `wanChannel.sendExtent(E, bytes, manifestRef)` → `Applier.shipExtent(E, bytes, manifestRef)`.
5. The Applier's **first** action is `isApplied(E)`. The probe returns `true`.
6. The Applier returns `Ack.duplicate(E)` **without** re-writing bytes or re-touching the OSD. It increments the `duplicateExtents` counter.
7. The Shipper treats `duplicate` as success — the cursor advances. The manifest's `shippedAt` is eventually set, just as if the original ack had arrived.

The applied-extent record is the entire correctness argument: any number of duplicate ships are safe because the **first thing** the Applier does is consult the idempotency record. The fact that sealed extents are immutable (their byte content is fixed for the lifetime of the extent) is what makes the "same bytes" assertion in step 4 trivially true.

### 5.3 Failover replay

1. Operator (or monitor) declares the source region unavailable; the target region is promoted.
2. The promoted region's `Applier.triggerFailoverReplay(namespace)` walks every `SnapshotManifestRecord` with `shippedAt != null` and every `ExtentRef` inside, asserting that the corresponding `AppliedExtentRecord` exists.
3. Any missing apply (manifest says shipped, applied-extent record missing) indicates a manifest divergence — the recovery path re-applies from the still-present source OSD bytes if reachable, or surfaces a hard error if the source is gone. Recovered manifests are added to a reconciliation report.
4. The newly-promoted region begins serving clients. It runs both Shipper and Applier — Shipper toward a future replacement region, Applier still listening for any tail traffic from the (now-recovering) old source.

---

## 6. Invariants the implementation must hold

After a `Shipper.onExtentSealed(E)` returns:
- `E` is present in the current snapshot manifest's `extents` list with status "queued".
- The extent's bytes will be shipped at least once. (At-least-once delivery.)

After an `Applier.shipExtent(E, bytes, manifestRef)` returns `Ack.ok(...)` or `Ack.duplicate(...)`:
- An `AppliedExtentRecord(E, ...)` exists in the target KV.
- The extent bytes are durably written to a target-region OSD (in the `ok` case) or were durably written by a prior call (in the `duplicate` case).
- A subsequent call with the same `E` returns `Ack.duplicate(E)` and performs no OSD write. **Idempotent.**

For the shipper's `lastShippedSnapshotId` cursor:
- The cursor advances from snapshot `S` to snapshot `S+1` **only** when every `ExtentRef` in `S` has received an `Ack` with `accepted == true`, AND every `JournalRecord` in `S.journalWindow` has been acked.
- A snapshot's `shippedAt` is set in the same transaction (or single KV write) as the cursor advance.

For any extent `E` ever shipped:
- `Applier.isApplied(E)` returns `true` for the rest of the namespace's lifetime. (Until crypto-shred GC deletes the namespace's KV entries wholesale.)

The service must **never** double-write an extent's bytes on the target side. A duplicate ship is a no-op write; the idempotency check happens before the OSD write call.

---

## 7. Failure modes & required handling

| Trigger | Detection | Required handling |
|---|---|---|
| WAN channel slow / congested | `rpoLagSeconds()` exceeds a threshold; the oldest unshipped extent ages | Emit a `dfs_georep_rpo_lag_seconds` gauge; admin alert at threshold; **do not** drop work — back-pressure the shipper queue and let it grow. Tenants with strict RPO contracts can opt the gateway into refusing writes when RPO would breach (out of scope here — the gateway owns that policy). |
| Applier crash after write but before ack | Shipper's `ackTimeout` fires | Requeue the extent with `attemptCount++`. The Applier's `isApplied(E)` check on retry returns `Ack.duplicate(E)` — see §5.2. |
| Snapshot manifest divergence | Periodic `reconcileSnapshot(namespace, snapshotId)` returns a non-empty `ExtentStateDiff` | Re-ship extents listed in `missingOnTarget`. `extraOnTarget` (applied without a source manifest entry) indicates a serious source-side bug — log loudly and surface to ops; do not auto-delete. |
| Failover replay storm | Cluster-monitor declares failover; clients reconnect en masse | The geo-rep service is not the throttle layer (that's dmClock at the OSD). What this service does on failover: `triggerFailoverReplay()` runs **single-threaded** to avoid amplifying load on the target MDS; it processes manifests in `createdAt` order so the namespace's metadata graph rebuilds in causal order. |
| WAN channel drops a packet (loopback drop probability fires) | `Ack.rejected(extentId, "wan_drop")` | Shipper retries with exponential backoff (100ms, 200ms, 400ms, ..., capped at 30s). Eventually surfaces as RPO lag if drops persist. |
| Extent bytes corrupted in transit | Checksum mismatch on the applier (computed SHA-256 of received bytes != `ExtentRef.checksum`) | `Ack.rejected(extentId, "checksum_mismatch")`. Shipper retries up to N times then surfaces a hard error to ops. |
| Source region gone, partial manifests on target | `triggerFailoverReplay` returns count > 0 of unrecoverable manifests | Surface the reconciliation report; the target region begins serving with a documented data-loss window equal to `rpoLagSeconds()` at the moment of failure. RPO is a contract, not a promise. |

---

## 8. Testing acceptance criteria

Required tests in `dfs-georep/src/test/java/com/hkg/dfs/georep/`. Use the `LoopbackChannel` so tests don't touch a network.

| Test class | Test method | Asserts |
|---|---|---|
| `ShipperBasicTest` | `sealedExtentEnqueuesIntoCurrentSnapshot` | After `onExtentSealed("e1")`, the current snapshot manifest contains an `ExtentRef("e1", ...)`. |
| `ShipperBasicTest` | `snapshotRollAdvancesCurrent` | `rollSnapshot()` returns the previous id; subsequent extents land in a new snapshot. |
| `ShipperBasicTest` | `rpoLagMatchesOldestUnshippedAge` | With one queued unshipped extent created at T, `rpoLagSeconds()` at T+5 returns 5 (±1). |
| `ApplierIdempotencyTest` | `firstApplyReturnsOk` | A fresh extent ship returns `Ack.ok`, writes the `AppliedExtentRecord`, writes bytes. |
| `ApplierIdempotencyTest` | `duplicateApplyReturnsDuplicateAndDoesNotWrite` | Re-ship of the same extent returns `Ack.duplicate`; OSD write count stays at 1. |
| `ApplierIdempotencyTest` | `appliedExtentRecordSurvivesRestart` | After simulating Applier restart, `isApplied("e1")` still returns true. |
| `ShipperApplierLoopbackTest` | `extentRoundTrip` | Seal extent → wait for snapshot → assert applied on target side, bytes identical. |
| `ShipperApplierLoopbackTest` | `journalRecordRoundTrip` | Emit a `CREATE_INODE` journal record → assert it is applied on the target MDS. |
| `ShipperApplierLoopbackTest` | `snapshotShippedAtSetWhenAllAcked` | After all extents in a snapshot are acked, `shippedAt != null`. |
| `ShipperApplierLoopbackTest` | `cursorOnlyAdvancesWhenApplierAcks` | Configure 100% drop rate → ack never arrives → `lastShippedSnapshotId` does not advance. |
| `LoopbackChannelTest` | `simulatedLatencyHonored` | Send with 100ms simulated latency; measured wall-clock delay ≥ 100ms. |
| `LoopbackChannelTest` | `dropProbabilityHonored` | Send 1000 packets at 0.5 drop probability; observed drops within ±5% of 500. |
| `CrashMidApplyTest` | `crashAfterWriteBeforeAckLeavesIdempotencyRecord` | Simulate Applier crash post-KV-write pre-ack; re-ship returns `Ack.duplicate`. |
| `FailoverReplayTest` | `triggerReplayFindsMissingApplies` | Synthesize a manifest with `shippedAt` set but missing `AppliedExtentRecord`; `triggerFailoverReplay` reports the gap. |
| `FailoverReplayTest` | `replayProcessesManifestsInCausalOrder` | Manifests with parent links; replay touches them in `createdAt` order. |
| `ReconcileTest` | `reconcileFindsExtraOnTarget` | Write an `AppliedExtentRecord` with no matching source manifest entry; `reconcileSnapshot` reports it as `extraOnTarget`. |
| `ReconcileTest` | `reconcileFindsMissingOnTarget` | Drop an `AppliedExtentRecord` for an extent the source manifest claims shipped; `reconcileSnapshot` reports it as `missingOnTarget`. |

All tests pass under `./gradlew :dfs-georep:test`.

---

## 9. Stubs allowed / out of scope (initial pass)

- **WAN channel is loopback only.** The only `WanChannel` implementation is `LoopbackChannel` — an in-process / in-JVM channel that simulates configurable latency (`Duration`) and packet loss (`double dropProbability`). Both shipper and applier run in the same JVM and the channel hand-walks frames between them on a worker thread. The real TCP/QUIC wire protocol — frame format, congestion control, connection management, retries at the transport layer — is **out of scope**. The interface (`WanChannel`) is shaped to make swapping in a real implementation a localised change.
- **Cross-region IAM and mTLS auth.** The real service mutually authenticates shipper and applier using the cluster monitor as a certificate authority (see §3-cluster-monitor in the essay). Here, the loopback channel is trusted by construction — no signature checks, no TLS. The applier's `bind(...)` is the only thing that gates which shipper can talk to it.
- **Snapshot scheduling is a fixed periodic interval.** `GeoRepConfig.snapshotInterval` (default 5s). Adaptive scheduling based on observed RPO target, tenant policy, or WAN bandwidth is **out of scope**.
- **No bidirectional replication.** This module ships one direction. A real cell with two-way replication runs the module twice with swapped roles; conflict resolution for concurrent writes on both sides is **out of scope**.
- **Storage and KV are in-memory.** Just like `dfs-lease`, both the snapshot manifest store and the applied-extent store are `ConcurrentHashMap`s on each side. Production persists these to a transactional KV.
- **Journal record payload is opaque.** This module treats `JournalRecord.payload` as a `byte[]` and ships it intact; the applier's MDS adapter is responsible for deserialising and applying it. A real implementation would have a versioned schema with forward-compat rules.
- **No back-pressure-to-gateway hook.** The shipper exposes `rpoLagSeconds()`; the gateway uses it. Wiring the gateway to refuse writes when RPO would breach is the gateway's responsibility — out of scope here.

---

## 10. Dependencies

### Build

```groovy
// In root build.gradle, add (this is §11 work, not done here):
project(':dfs-georep') {
    dependencies {
        api            project(':dfs-common')
        api            project(':dfs-mds')           // §1 — journal records
        api            project(':dfs-storage')       // §4 — sealed extent bytes
        api            project(':dfs-lease')         // sealed-extent metadata (Extent, ExtentService)

        testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    }
}
```

`dfs-georep` does **not** depend on `dfs-gateway-s3`, `dfs-gateway-posix` (§7, §8) or `dfs-client` (§6). The gateways observe RPO state via the geo-rep service's metrics, not by direct API call.

### Runtime

- JDK 17+.
- No external libraries beyond JDK and JUnit (initial pass).

### Wiki concepts implemented

- [`extent-sealing`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/extent-sealing.md) — the immutability invariant that makes async shipping race-free.
- [`async-geo-replication`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/async-geo-replication.md) (create if missing).
- [`idempotent-apply`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/idempotent-apply.md) (create if missing).

### Essay section

[§9. Geo-Replication Service (Async Log Shipper)](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system/distributed-file-system-full.md#9-geo-replication-service-async-log-shipper)

---

## 11. Implementation checklist

Roughly in order. Each item should land with its tests.

**Foundations**
- [ ] `GeoRepConfig` record (source/target region, snapshot interval, worker count, ack timeout)
- [ ] `JournalRecord` + `JournalOp` enum
- [ ] `SnapshotManifestRecord`, `ExtentRef`, `JournalWindow` records
- [ ] `AppliedExtentRecord` record
- [ ] `Ack`, `ShipperState`, `ApplierState`, `ExtentStateDiff` records

**Loopback WAN channel** (build first — both Shipper and Applier tests need it)
- [ ] `WanChannel` interface
- [ ] `LoopbackChannel` implementation with configurable latency + drop probability
- [ ] `LoopbackChannelTest.simulatedLatencyHonored`
- [ ] `LoopbackChannelTest.dropProbabilityHonored`

**Snapshot manifest manager**
- [ ] In-memory snapshot store (`ConcurrentHashMap<UUID, SnapshotManifestRecord>`)
- [ ] `rollSnapshot()` (close current, open new with parent link)
- [ ] `shippedAt` advance logic (set when all `ExtentRef`s in the manifest are acked)
- [ ] Snapshot-store unit tests covering roll, parent linkage, and `shippedAt` advance

**Applied-extent tracker**
- [ ] In-memory applied store (`ConcurrentHashMap<String, AppliedExtentRecord>`)
- [ ] `isApplied(extentId)` probe
- [ ] `ApplierIdempotencyTest.appliedExtentRecordSurvivesRestart`

**Shipper**
- [ ] `Shipper` interface and default impl
- [ ] `onExtentSealed` + `onJournalRecord` hooks (enqueue into the current snapshot)
- [ ] Background worker pool (`shipperWorkerThreads`) draining the ship queue
- [ ] Exponential-backoff retry on `Ack.rejected`
- [ ] `rpoLagSeconds()` based on `oldestUnshippedAt`
- [ ] `ShipperBasicTest.*` (3 tests)

**Applier**
- [ ] `Applier` interface and default impl
- [ ] `shipExtent` with idempotency probe → OSD write → `AppliedExtentRecord` write → ack
- [ ] `shipJournalRecord` with `lsn`-based idempotency (skip if `lsn <= lastAppliedLsn`)
- [ ] `reconcileSnapshot(namespace, snapshotId)` diff computation
- [ ] `ApplierIdempotencyTest.*` (3 tests)
- [ ] `ReconcileTest.*` (2 tests)

**End-to-end loopback**
- [ ] Wire Shipper + LoopbackChannel + Applier in a single JVM for tests
- [ ] `ShipperApplierLoopbackTest.*` (4 tests)

**Failover replay path**
- [ ] `triggerFailoverReplay(namespace)` — single-threaded scan over manifests in `createdAt` order
- [ ] `CrashMidApplyTest.crashAfterWriteBeforeAckLeavesIdempotencyRecord`
- [ ] `FailoverReplayTest.*` (2 tests)

**Wiring**
- [ ] Add `':dfs-georep'` to `settings.gradle`
- [ ] Add dependency block to root `build.gradle`
- [ ] All tests pass under `./gradlew :dfs-georep:test`

**Documentation**
- [ ] Write `docs/modules/dfs-georep.md` in the existing 7-section format (Role → Wiki anchor → Public API surface → Internal structure → Key tests → Where it fits → Stubs and departures from production)
- [ ] Add `dfs-georep` row to `docs/modules/README.md` index
- [ ] Update [`wiki/my-explanations/distributed-file-system-services.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md) §9 status from ❌ Missing → ✅ Implemented

When all boxes are ticked, this SPEC.md can be moved to `docs/specs/dfs-georep.md` for historical reference. The `docs/modules/dfs-georep.md` doc becomes the contract going forward.
