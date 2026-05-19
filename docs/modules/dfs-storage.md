# dfs-storage

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The BlueStore-style OSD. Two write paths chosen by payload size:

- **Large writes** (`writeLarge`): copy-on-write into a fresh extent slot, then commit the metadata transaction linking it to the object. No journaling double-write.
- **Small writes** (`writeSmall`): in-memory WAL queue. A background flush (`flushDeferred`) merges WAL entries into a properly-aligned extent later.

Both paths CRC32c-protect every blob and verify on read. Corrupt blob → `ChecksumMismatchException`.

## 2. Wiki anchor

[`wiki/tradeoffs/posix-fs-vs-raw-block-backend`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/posix-fs-vs-raw-block-backend.md). The wiki argues that POSIX local file systems (ext4, XFS) impose 5-10× write amplification on cluster-storage workloads; raw-block backends like BlueStore achieve 1-2×. This module shows the shape of the raw-block side.

## 3. Public API surface

```java
package com.hkg.dfs.storage;

public final class Osd {
    public synchronized void writeLarge(String extentId, long offset, byte[] bytes);
    public synchronized void writeSmall(ObjectId obj, byte[] bytes);
    public synchronized int flushDeferred(String extentId, long baseOffset);
    public synchronized byte[] read(String extentId, long offset, int length);

    public synchronized void corruptForTest(String extentId, long offset);
    public synchronized int walSize();
}

public final class ChecksumMismatchException extends RuntimeException { ... }
```

Source: `dfs-storage/src/main/java/com/hkg/dfs/storage/Osd.java`.

`SMALL_WRITE_THRESHOLD = 4096` bytes is the cut-off between the two paths.

## 4. Internal structure

- **`ConcurrentSkipListMap<String, byte[]> data`** — the stand-in for RocksDB. Key is `extentId + "@" + offset`; value is the bytes. The skip-list is overkill for the test workload but matches the "sorted-by-key" semantics RocksDB exposes.
- **`HashMap<String, Integer> crc`** — per-blob CRC32c. Looked up on every read.
- **`Deque<WalEntry> wal`** — the in-memory WAL queue for small writes. An `ArrayDeque` for FIFO behaviour.
- **`record WalEntry(ObjectId obj, byte[] bytes)`** — payload + identifier.

The two-path write flow:

```
writeLarge(extentId, offset, bytes):
  copy = Arrays.copyOf(bytes)                 // defensive copy
  data.put(extentId + "@" + offset, copy)     // metadata commit
  crc.put(... , crc32c(copy))

writeSmall(obj, bytes):
  if bytes.length > 4096: error
  wal.add(new WalEntry(obj, defensive_copy))

flushDeferred(extentId, baseOffset):
  while !wal.isEmpty():
    e = wal.poll()
    writeLarge(extentId, baseOffset, e.bytes)
    baseOffset += e.bytes.length
```

The `read` path fetches the blob, recomputes CRC, throws if mismatch. `corruptForTest` is the explicit hook tests use to prove the checksum mechanism fires.

## 5. Key tests

18 tests in `OsdTest`.

| Test | Demonstrates |
|---|---|
| `writeLargeThenReadRoundTrips` | Round-trip integrity for a large write. |
| `writeSmallQueuesToWal` | A small write enqueues to the WAL; `walSize` reflects the queue depth. |
| `flushDeferredDrainsWal` | After `flushDeferred`, `walSize() == 0`. |
| `flushDeferredMakesDataReadable` | Bytes flushed from the WAL into an extent are readable at the chosen offset. |
| `writeSmallTooLargeRejected` | Bytes > 4 KB throw `IllegalArgumentException`. |
| `crcMismatchDetected` | After `corruptForTest`, the next read throws `ChecksumMismatchException`. |
| `writeLargeIsDefensive` | Mutating the source array after `writeLarge` does not affect what `read` returns. |
| `differentOffsetsAreIndependent` | Two writes to the same extent at different offsets do not collide. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator` (acts as the OSD in scenarios).

**Downstream dependencies:** `dfs-common`.

**The dependency rule:** the OSD knows about extents (via opaque IDs) and objects (via `ObjectId` for WAL entries). It does NOT know about placement groups, capabilities, leases, or replication. Those are someone else's concerns.

## 7. Stubs and departures from production

- **`ConcurrentSkipListMap` instead of RocksDB.** Real BlueStore stores object metadata in RocksDB column families on top of BlueFS on raw block. This is the single biggest simplification in the repo. The shape (sorted key/value with range queries possible) is preserved.
- **No bitmap allocator integration.** This module accepts an `offset` from the caller; in reality the offset is whatever `dfs-allocator.allocate()` returned. The wiring would happen here in a real implementation.
- **No durability boundary.** A real OSD calls `fsync` after every WAL write before returning. This module's writes are all in-process; there's no concept of "synced to disk".
- **No journaling for the metadata transaction.** Real BlueStore writes object-to-extent mappings to the RocksDB WAL atomically. Here, `data.put` is the only step.
- **No compaction.** Real RocksDB compaction interleaves with hot writes and affects p99. This module has no compaction.
