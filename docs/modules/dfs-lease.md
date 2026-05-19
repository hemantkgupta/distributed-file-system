# dfs-lease

> Last reconciled with the repo on 2026-05-20.

## 1. Role

Two consistency primitives bundled into one module:

- **Chunk leases** — time-bounded grants from the monitor to a primary OSD authorising it to serialise writes for a chunk during the lease term. The GFS lease mechanism: no Paxos on the hot path; consistency is bought via "the primary's order is the order".
- **Extent sealing** — append-only `Extent` objects that can be `OPEN` or `SEALED`. Once sealed, an extent rejects further appends; its byte length is the durable commitment all replicas agree on.

The two primitives compose: a chunk-lease's primary OSD is the one writing into the open extent; if the primary fails or a partition cuts the replica set, sealing the extent forces a new allocation rather than reconciling divergent tails.

## 2. Wiki anchors

- [`wiki/concepts/chunk-lease`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/chunk-lease.md)
- [`wiki/concepts/extent-sealing`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/extent-sealing.md)

## 3. Public API surface

```java
package com.hkg.dfs.lease;

public final class LeaseService {
    public LeaseService();                              // 60-second term, system clock
    public LeaseService(Clock clock, Duration term);    // injectable for tests

    public ChunkLease grant(ChunkId chunk, OsdId primary);
    public ChunkLease renew(ChunkId chunk);
    public void revoke(ChunkId chunk);
    public Optional<ChunkLease> get(ChunkId chunk);
}

public record ChunkLease(ChunkId chunkId, OsdId primaryOsd, Instant expiresAt) {
    public boolean isExpired(Instant now);
}

public final class ExtentService {
    public Extent open(String extentId);
    public Extent append(String extentId, byte[] bytes);   // throws if sealed
    public Extent seal(String extentId);
    public long sealedLength(String extentId);             // throws if not SEALED
    public Extent get(String extentId);                    // throws if missing
}

public record Extent(String extentId, ExtentStatus status, long length) { ... }

public enum ExtentStatus { OPEN, SEALED }
```

Source: `dfs-lease/src/main/java/com/hkg/dfs/lease/`.

## 4. Internal structure

- **`LeaseService`** — `ConcurrentHashMap<ChunkId, ChunkLease>`. Clock-injectable for tests so lease expiry can be advanced without sleeping. The `grant` method is overwriting (not insert-if-absent) by design — a monitor recovering from a primary failure needs to re-grant to a new OSD.
- **`ChunkLease`** — value record. `isExpired(now)` is the canonical liveness check.
- **`ExtentService`** — `ConcurrentHashMap<String, Extent>`. The `append` and `seal` methods both use `compute(...)` so the state transition is atomic. After seal, all `append` calls throw `IllegalStateException`.
- **`Extent.grow(long delta)` / `.seal()`** — helper methods that produce the next state (returning a new record, since `Extent` is immutable).

## 5. Key tests

22 tests across `LeaseServiceTest` and `ExtentServiceTest`.

| Test | Demonstrates |
|---|---|
| `LeaseServiceTest.grantStoresLease` | Granted lease is retrievable and carries the primary OSD. |
| `LeaseServiceTest.renewExtendsExpiry` | Renew updates `expiresAt` without changing primary. |
| `LeaseServiceTest.revokeRemoves` | After revoke, `get` returns empty. |
| `LeaseServiceTest.leaseExpiresAfterTerm` | `ChunkLease.isExpired(future)` returns true once the term elapses. |
| `ExtentServiceTest.appendGrowsLength` | Multiple appends accumulate; length tracks. |
| `ExtentServiceTest.sealedExtentRejectsAppend` | After seal, append throws. |
| `ExtentServiceTest.sealedLengthMatchesAppends` | `sealedLength` returns the byte-count at seal time; subsequent reads agree. |
| `ExtentServiceTest.openDuplicateFails` | Re-opening an existing extent throws. |
| `ExtentServiceTest.primaryFailureRevokesAndAllowsSealing` | Composed scenario: lease revoke + extent seal during primary failover. |

## 6. Where it fits

**Upstream consumers:** `dfs-node` (grants lease + opens extent in the PUT path); `dfs-monitor` (delegates lease grant/revoke); `dfs-simulator`.

**Downstream dependencies:** `dfs-common`.

**The dependency rule:** the lease and extent services don't know about the file system, the namespace, or any specific OSD's local state. They are coordination primitives.

## 7. Stubs and departures from production

- **In-memory only.** Production leases and extent metadata are persisted to a transactional KV store so a monitor restart doesn't drop them.
- **No lease handoff protocol.** When a primary wants to step down (load shed), production has a graceful handoff. Here, you `revoke` and `grant` to a new OSD with no coordination.
- **No fencing tokens.** Production leases often carry a monotonically-increasing token that the OSD uses to reject stale writes from a previous primary. This module doesn't.
- **Extent sealing doesn't propagate to replicas.** A real seal forces every replica to truncate to the agreed length. Here, "sealing" is a flag on the in-memory record.
