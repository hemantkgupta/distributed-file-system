# dfs-placement

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The Block Layer lookup. Two responsibilities:

- **Hash an object to a placement group** (PG): deterministic, local-CPU-only.
- **Look up the PG's current physical OSDs**: a small KV lookup, decoupled from the per-object hash so topology changes can update the lookup table without touching every client.

This is the "lookup" half of the hybrid placement scheme the wiki recommends. CRUSH does the per-object hop; this module does the per-host hop.

## 2. Wiki anchor

[`wiki/patterns/hybrid-deterministic-lookup-placement`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/hybrid-deterministic-lookup-placement.md). The wiki argues that pure CRUSH breaks above 10^12 objects (map-propagation storms) and pure lookup costs too much DB footprint; hybrid is the production answer. This module is the lookup-tier side.

## 3. Public API surface

```java
package com.hkg.dfs.placement;

public final class Placement {
    public Placement(int pgCount);
    public PgId objectToPg(ObjectId obj);   // deterministic FNV-1a, mod pgCount
    public int pgCount();
}

public final class BlockLayer {
    public PgLocation putInitial(PgId pg, List<OsdId> osds);
    public Optional<PgLocation> lookup(PgId pg);
    public PgLocation updateLocation(PgId pg, List<OsdId> newOsds);  // bumps generation
    public int size();
}

public record PgLocation(Generation generation, List<OsdId> osds) {
    public OsdId primary();    // first OSD
}
```

Source: `dfs-placement/src/main/java/com/hkg/dfs/placement/`.

## 4. Internal structure

- **`Placement`** — pure CPU. Hashes `ObjectId.value()` with FNV-1a-32, mods by configured PG count. No I/O, no state. The PG count is a constant chosen at pool creation in production (typical: ~100–200 PGs per OSD).
- **`BlockLayer`** — `ConcurrentHashMap<PgId, PgLocation>`. The interesting method is `updateLocation`, which uses `compute` to atomically bump the generation tag:
  ```java
  return table.compute(pg, (k, cur) -> {
      if (cur == null) throw new IllegalStateException(...);
      return new PgLocation(cur.generation().next(), newOsds);
  });
  ```
  The generation bump is the wire signal that lets the rest of the system detect stale views.
- **`PgLocation`** — value record. `primary()` is `osds.get(0)` by convention; the chunk-lease module relies on this to know which OSD to grant the lease to.

## 5. Key tests

14 tests in `dfs-placement/src/test/`.

| Test | Demonstrates |
|---|---|
| `PlacementTest.hashIsDeterministic` | Same `ObjectId` always returns the same `PgId`. |
| `PlacementTest.distributesAcrossPgs` | Over many random objects the load spreads across every PG. |
| `BlockLayerTest.updateIncrementsGeneration` | After `updateLocation`, the returned `PgLocation.generation()` is one greater. |
| `BlockLayerTest.updateChangesOsdList` | `lookup` reflects the most recent `updateLocation`. |
| `BlockLayerTest.duplicateInitialRejected` | Idempotency: re-initialising the same PG throws. |

## 6. Where it fits

**Upstream consumers:** `dfs-node` (resolves an object to its OSDs); `dfs-simulator`.

**Downstream dependencies:** `dfs-common` (api), `dfs-crush` (implementation, declared in `build.gradle` but not yet imported in source).

**The dependency rule:** the Block Layer never reaches into CRUSH. CRUSH may seed the initial PG-to-OSD mapping (via `dfs-node`), but subsequent changes happen only through `BlockLayer.updateLocation`.

## 7. Stubs and departures from production

- **In-memory KV store.** Production Block Layer is backed by a sharded distributed KV store (Bigtable, ZippyDB). This module is a `ConcurrentHashMap`.
- **No client-side caching.** Production clients cache lookups with TTLs; here every lookup is a direct map read.
- **No transactional updates.** Production updates to the Block Layer are part of a larger transaction that also rebalances PG state (peer count, recovery state). This module updates one row at a time.
- **PG splitting is not implemented.** When a PG's load grows past a threshold, production systems split it into two. This module's PG count is fixed at construction.
