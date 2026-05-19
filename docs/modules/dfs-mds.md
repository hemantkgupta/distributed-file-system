# dfs-mds

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The Metadata Server cluster. Two responsibilities bundled:

- **POSIX namespace** — directories, files, inodes, dentries via `mkdir`, `create`, `stat`, `rename`.
- **Capability vector management** — `open(path, clientId, requested)` issues a cap to a client; on a conflicting access, the MDS `recall`s the existing cap before granting.

Plus the load-balancing primitive: `SubtreePartitioner` tracks which MDS shard owns each subtree and supports `migrate`.

## 2. Wiki anchors

- [`wiki/concepts/capability-vector`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/capability-vector.md)
- [`wiki/concepts/dynamic-subtree-partitioning`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dynamic-subtree-partitioning.md)
- [`wiki/systems/cephfs-mds`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/systems/cephfs-mds.md)

## 3. Public API surface

```java
package com.hkg.dfs.mds;

public final class MdsCluster {
    public Inode mkdir(String path);
    public Inode create(String path);
    public Optional<Inode> stat(String path);
    public synchronized CapabilityVector open(String path, String clientId, CapabilityVector requested);
    public void recall(String path);
    public Optional<CapabilityVector> heldBy(String path, String clientId);
    public SubtreePartitioner partitioner();
    public Map<String, Inode> snapshot();
    public void rename(String fromPath, String toPath);
}

public record Inode(long inodeId, long parentId, String name, InodeType type) {}
public enum InodeType { FILE, DIR }

public record CapabilityVector(Set<Cap> caps) {
    public enum Cap { READ, WRITE, CACHE_READ, CACHE_WRITE, EXCLUSIVE }
    public boolean hasRead();
    public boolean hasWrite();
    public boolean hasExclusive();
    public static CapabilityVector readOnly();
    public static CapabilityVector readWrite();
    public static CapabilityVector exclusive();
}

public final class SubtreePartitioner {
    public synchronized void assign(String subtree, int mdsId);
    public synchronized Optional<Integer> ownerOf(String path);
    public synchronized void migrate(String subtree, int fromMds, int toMds);
    public synchronized int subtreeCount();
}
```

Source: `dfs-mds/src/main/java/com/hkg/dfs/mds/`.

## 4. Internal structure

- **`MdsCluster.inodes`** — `ConcurrentHashMap<String, Inode>` keyed by absolute path. Toy compared to real MDS inode tables but exposes the right shape.
- **`MdsCluster.caps`** — `ConcurrentHashMap<String, CapHolder>` tracking which client holds the cap for each path. `CapHolder` is a private record bundling `(clientId, CapabilityVector)`.
- **`MdsCluster.inodeSeq`** — `AtomicLong` generating monotonic inode IDs.
- **`SubtreePartitioner.ownership`** — `Map<String, Integer>` keyed by path prefix. `ownerOf(path)` does longest-prefix match — the same algorithm CephFS uses to find a path's owning MDS.
- **`CapabilityVector`** — encodes the bit-flag-style capability state CephFS uses (the wiki's letter-code vector). `conflicts(held, wanted)` is the synchronisation check inside `open`.

The `open` method's core logic:

```java
public synchronized CapabilityVector open(String path, String clientId, CapabilityVector requested) {
    CapHolder existing = caps.get(path);
    if (existing != null && !existing.clientId.equals(clientId)
        && conflicts(existing.cap, requested)) {
        recall(path);   // synchronously remove the existing cap
    }
    caps.put(path, new CapHolder(clientId, requested));
    return requested;
}
```

The `recall` is synchronous (just a map remove) — a real MDS would send an RPC to the client, wait for the client to flush dirty writes, and only then proceed.

## 5. Key tests

22 tests across `MdsClusterTest` (14) and `SubtreePartitionerTest` (8).

| Test | Demonstrates |
|---|---|
| `MdsClusterTest.mkdirCreatesDirInode` | After mkdir, stat returns the new directory inode. |
| `MdsClusterTest.createCreatesFileInode` | After create, stat returns a FILE inode whose `parentInodeId` matches the parent dir. |
| `MdsClusterTest.openGrantsCapability` | After open, `heldBy(path, clientId)` returns the requested cap. |
| `MdsClusterTest.capRecallOnConflict` | Client B opens with write while client A holds write → client A's cap is revoked. |
| `MdsClusterTest.renameInvalidatesCaps` | rename clears any cap on the old path. |
| `MdsClusterTest.exclusiveCapHasAllFlags` | An exclusive cap has read, write, and exclusive bits set. |
| `SubtreePartitionerTest.longestPrefixWins` | With assignments `/home → 0` and `/home/alice → 1`, `ownerOf("/home/alice/file") == 1`. |
| `SubtreePartitionerTest.migrateFromWrongOwnerFails` | Migrating from the wrong MDS throws. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator`.

**Downstream dependencies:** `dfs-common`.

**The dependency rule:** the MDS knows about clients (by opaque string ID), inodes, caps, and subtrees. It does NOT know about OSDs, placement, leases, or the data plane.

## 7. Stubs and departures from production

- **`ConcurrentHashMap` instead of RADOS-backed durability.** Real CephFS MDS stores inodes/dentries as RADOS objects in a dedicated metadata pool with a journal. On restart, replay rebuilds in-memory state. Here, an MDS restart loses everything.
- **No actual cap recall RPC.** Production cap recall sends a message to the client, awaits acknowledgement (with a deadline), and force-evicts the client if it stalls. This module just removes the entry.
- **No cap classes / bit flags.** Production caps have classes (Auth, Link, File, Xattr) and modes (Shared, Exclusive). This module's `CapabilityVector` reduces to `hasRead`/`hasWrite`/`hasExclusive` — the structural distinction the conflict check needs.
- **Rename is path-string only.** A real cross-subtree rename is a distributed transaction across MDS shards. Here, the call just moves a map entry.
- **No load monitor / auto-migration.** The wiki's "dynamic subtree partitioning" implies an active load balancer migrating subtrees. This module exposes `migrate` as an explicit operation; no automatic policy.
