# dfs-node

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The composition module. Wires `dfs-crush` + `dfs-placement` + `dfs-lease` into a single `NodeApi.put(obj, bytes)` entry point so integration tests can exercise the foundation modules together. It deliberately stubs out the actual replica writes — the focus is on showing the *control sequence*, not the data path.

This is what later phases (storage backend, control plane) plug into to make a complete system.

## 2. Wiki anchor

This module composes the patterns from [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md) — specifically the write-path narrative in §High-Level Design.

## 3. Public API surface

```java
package com.hkg.dfs.node;

public final class NodeApi {
    public NodeApi(Placement placement, BlockLayer blockLayer, Crush crush,
                   LeaseService leases, ExtentService extents, int replicationFactor);

    public PutResult put(ObjectId obj, byte[] bytes);
}

public record PutResult(PgId pg, List<OsdId> osds, ChunkId chunk,
                        String extentId, int writtenBytes) {}
```

Source: `dfs-node/src/main/java/com/hkg/dfs/node/NodeApi.java`.

## 4. Internal structure

One class, one method. The body of `put`:

```java
public PutResult put(ObjectId obj, byte[] bytes) {
    PgId pg = placement.objectToPg(obj);
    PgLocation loc = blockLayer.lookup(pg).orElseGet(() -> {
        List<OsdId> osds = crush.place(obj, replicationFactor, BucketType.RACK);
        return blockLayer.putInitial(pg, osds);
    });
    ChunkId chunk = ChunkId.of(Math.abs(obj.value().hashCode()));
    leases.grant(chunk, loc.primary());
    String extentId = "ext-" + pg.value();
    try { extents.get(extentId); }
    catch (IllegalStateException missing) { extents.open(extentId); }
    extents.append(extentId, bytes);
    return new PutResult(pg, loc.osds(), chunk, extentId, bytes.length);
}
```

Three things to notice:

- **First-touch seeds the Block Layer with CRUSH.** If a PG has no entry yet, CRUSH supplies the initial OSDs. Subsequent calls hit the Block Layer directly. This is the "hybrid placement" pattern in practice — CRUSH on first touch, lookup thereafter.
- **Lease granted to the primary.** `loc.primary()` is `osds.get(0)`. The lease is granted before any append; the contract is "the primary serialises writes for this chunk".
- **Extents are PG-scoped.** `"ext-" + pg.value()` is the (toy) extent ID convention. A real cluster scopes extents per (PG, generation) and rolls over on seal.

## 5. Key tests

6 tests in `NodeApiTest`.

| Test | Demonstrates |
|---|---|
| `putReturnsResult` | Result carries the PG, OSDs, chunk, extent, and byte count. |
| `putIsDeterministicInPg` | Same `ObjectId` always lands in the same PG. |
| `putUsesReplicationFactor` | First-touch CRUSH seeds the Block Layer with the configured number of OSDs. |
| `putAppendsToSameExtent` | Two writes to the same PG share an extent whose length grows by exactly `bytes.length` each call. |
| `putToDifferentKeysMayLandInDifferentPgs` | Distinct keys fan out across PGs (not pinned to one). |
| `putGeneratesNonEmptyExtentId` | The constructed extent ID is non-blank and PG-scoped. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator` (one of the bigger-picture exercisers).

**Downstream dependencies:** `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-common`.

**The dependency rule:** this module is the only module in Phase 1 that depends on the other foundation modules. Each of those, individually, has at most one Phase-1 sibling among its dependencies. This composition shape is what lets each foundation module be tested in isolation.

## 7. Stubs and departures from production

- **No actual byte writes to OSDs.** The bytes "go to" the extent service, which records the length but doesn't store the bytes. Real writes happen in `dfs-storage`, which Phase 2 introduces.
- **No replica handshake.** A production PUT would send the bytes to the primary, the primary would forward to secondaries, secondaries would ack, primary would ack the client. This module doesn't simulate that.
- **No idempotency or retry.** A real client retries on transient failures; this PUT is one-shot.
- **No verdict on success.** Real clients want to know "the bytes are durable at the configured replication level"; here, "success" is "the bytes were recorded in the in-memory extent service".
