# dfs-crush

> Last reconciled with the repo on 2026-05-20.

## 1. Role

Deterministic placement: given an `ObjectId`, a cluster map describing the topology, and a desired replication factor + failure domain, compute the OSDs that should hold the object's replicas. No central directory consulted. This is the half of the placement story that lives on the client; the other half (Block Layer lookup) is `dfs-placement`.

## 2. Wiki anchor

[`wiki/concepts/crush-placement-algorithm`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/crush-placement-algorithm.md). The wiki page covers the theoretical properties (O(log N) routing, minimal data motion under topology change, failure-domain awareness). This module is the working implementation.

## 3. Public API surface

```java
package com.hkg.dfs.crush;

public final class Crush {
    public Crush(CrushMap map);
    public List<OsdId> place(ObjectId obj, int replicationFactor, BucketType failureDomain);
}

public enum BucketType { ROOT, ROW, RACK, HOST, OSD }

public final class CrushMap {
    public CrushMap(CrushBucket root);
    public CrushBucket root();
}

public final class CrushBucket {
    // tree node: name, type, weight, children OR osdId (leaf)
    public boolean isLeaf();
    public OsdId osdId();
    public List<CrushBucket> children();
}

public final class StrawSelector {
    public CrushBucket select(List<CrushBucket> candidates, long seed, int replicaIdx);
}
```

Source: `dfs-crush/src/main/java/com/hkg/dfs/crush/`.

## 4. Internal structure

- **`CrushMap` + `CrushBucket`** — the topology tree. Buckets are weighted nodes; leaves carry `OsdId`. The tree shape encodes failure-domain hierarchy (root → row → rack → host → osd).
- **`StrawSelector`** — the hashed-weight-draw algorithm. For each candidate child, computes `hash(seed, name, replicaIdx)` and XORs against the bucket weight; picks the longest "straw". This is the property that gives CRUSH minimal data motion under topology change: adding a new bucket only steals from existing ones in proportion to weight, never reshuffles unaffected buckets.
- **`Crush.place(...)`** — the recursive walk down the tree. At each level, asks the selector for a child, skipping children whose failure domain has already been used by an earlier replica (so two replicas never land in the same rack when `failureDomain=RACK`).
- **`BucketType`** — enum of topology levels.

The hash function in `StrawSelector` is `splitmix64`-style with replica perturbation:

```java
long pertSeed = seed + attempt * 0x9E3779B97F4A7C15L;
```

Different attempts produce different draws; the SAME `(seed, replicaIdx)` always produces the same draw — the determinism property.

## 5. Key tests

18 tests in `dfs-crush/src/test/`.

| Test | Demonstrates |
|---|---|
| `CrushTest.crushPlaceIsDeterministic` | Same `(ObjectId, factor, domain)` always returns the same `List<OsdId>`. |
| `CrushTest.replicasAreInDifferentRacks` | With `factor=3, failureDomain=RACK`, the 3 returned OSDs are in 3 distinct racks. |
| `CrushTest.addingBucketOnlyMovesDataIn` | Adds a child bucket to a parent's child list; only ~`new/(old+new)` of draws shift to the new bucket. Existing buckets do not reshuffle data between themselves. |
| `CrushTest.distributionAcrossOsdsIsRoughlyBalanced` | Over many `ObjectId`s the load distribution across OSDs stays within a small tolerance of even weight. |
| `CrushTest.rejectsZeroReplication` | `replicationFactor<=0` throws `IllegalArgumentException`. |

## 6. Where it fits

**Upstream consumers:** `dfs-node` (composes CRUSH + placement on first touch); `dfs-simulator` (uses it for end-to-end PUT in scenarios).

**Downstream dependencies:** `dfs-common`.

**The dependency rule:** `dfs-crush` knows nothing about leases, extents, OSD internals, or repair. It is a pure topology-to-OSD function.

## 7. Stubs and departures from production

- **No `straw2` algorithm.** Ceph evolved from the original "straw" selector to "straw2" because the original has subtle weight-proportionality bugs near the tails. This module's `StrawSelector` is closer to straw than to straw2.
- **CrushMap is in-memory only.** Production CRUSH maps are serialized binary blobs the monitor publishes to clients; map version is a load-bearing concept. This module just holds the map.
- **No rule sets.** Production CRUSH supports per-pool rules ("take(root); chooseleaf 3 type=rack; emit") authored by operators. This module hard-codes the rule shape inside `Crush.place`.
- **No tunables.** Production CRUSH has many configurable tunables (`crush_choose_max_tries`, etc.). This module uses fixed values.

None of these limit the teaching value of the determinism + minimal-motion demonstration the tests prove.
