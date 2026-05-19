# ADR-0002: In-Memory Substrates Instead of Embedded RocksDB

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

Two of the wiki's load-bearing architectural pieces are persistent KV stores:

- **OSD metadata** (object → extent mappings, per-blob CRCs) — production uses RocksDB on top of BlueFS on a raw block device.
- **Block Layer / metadata KV tier** — production uses Bigtable, ZippyDB, or FoundationDB.

A faithful Java implementation would embed RocksDB via the jrocksdb JAR (~30 MB) and stand up a separate KV-store integration test harness. Both are real engineering work, and neither buys teaching value once a reader has read the wiki's explanation of why RocksDB on raw block is the BlueStore choice. The choice is whether to fight the engineering or to stub it.

## Decision

Use Java's built-in concurrent collections as the in-process stand-ins for both persistence layers:

- `ConcurrentSkipListMap<String, byte[]>` for the OSD's object/extent metadata (the sorted-key semantics RocksDB exposes).
- `ConcurrentHashMap<PgId, PgLocation>` for the Block Layer.
- `ConcurrentHashMap<String, Inode>` for the MDS namespace.
- `ConcurrentHashMap<DukId, SecretKey>` for the KMS.

Acknowledge the gap in every affected module's `## 7. Stubs and departures from production` section.

## Alternatives considered

**Embed RocksDB via jrocksdb.** ~30 MB JAR, native binaries per platform. Performance characteristics would more faithfully reflect production (LSM compaction, WAL, manifest). Teaching value: marginal. Build/setup cost: real. See also [ADR-0001](./0001-pure-java-no-jni.md).

**MapDB or LevelDB-backed store.** Lighter than RocksDB; still a binary dependency. Doesn't simulate LSM compaction faithfully anyway.

**SQLite via xerial JAR.** Faithful enough for transactional semantics; very different from the wiki's intended architecture. Would confuse readers expecting BlueStore-style raw-block CoW.

**Write to actual files on disk.** Disk I/O on tests adds variance; the test suite has 239 tests and needs to be < 1 minute. Also doesn't reflect what RocksDB does internally.

## Consequences

**Positive:**
- Tests are fast and deterministic. The full suite runs in ~5 seconds on a modern laptop.
- Memory usage stays predictable; no compaction pauses or LSM amplification.
- A reader can trace the entire data path through the JVM debugger without crossing a native boundary.
- Module code stays focused on the architectural primitive, not the persistence-layer mechanics.

**Negative:**
- Performance numbers are not production-representative. A `ConcurrentSkipListMap.put` is ~50 ns; a RocksDB `Put` is ~5 µs. The OSD's throughput in this repo is 100× faster than reality.
- No compaction story. Real LSM stores have to interleave compaction with foreground I/O; that's a major source of production p99 variance and is invisible here.
- No crash-recovery story. Real BlueStore persists everything; this repo loses state on JVM restart.
- The "BlueStore replaces FileStore for these specific reasons" argument from the wiki ([`tradeoffs/posix-fs-vs-raw-block-backend`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/posix-fs-vs-raw-block-backend.md)) can't be experimentally demonstrated here.

## Implementation pointers

- `dfs-storage/.../Osd.java` — `ConcurrentSkipListMap<String, byte[]> data` + `HashMap<String, Integer> crc`.
- `dfs-placement/.../BlockLayer.java` — `ConcurrentHashMap<PgId, PgLocation>`.
- `dfs-mds/.../MdsCluster.java` — `ConcurrentHashMap<String, Inode>`.
- `dfs-security/.../Kms.java` — `ConcurrentHashMap<DukId, SecretKey>`.
- `dfs-lease/.../{LeaseService, ExtentService}.java` — `ConcurrentHashMap` for both.

Each module's page documents the gap in its §7.

## Related

- [ADR-0001](./0001-pure-java-no-jni.md)
- [`getting-started.md`](../getting-started.md) — "Honesty about what this code is and isn't"
