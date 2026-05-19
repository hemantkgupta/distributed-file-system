# ADR-0006: Java `record` for Value Objects

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

Java 17 introduced records — compact syntactic sugar over immutable value classes with auto-generated `equals`, `hashCode`, `toString`, and accessor methods. The repo has ~30 candidate value types: identifiers (`ObjectId`, `ChunkId`, etc.), event types (`DurabilityEvent`), domain records (`PgLocation`, `ChunkLease`, `WorkItem`), result types (`PutResult`).

The convention question: when to use `record`, when to use a hand-written `class`?

## Decision

Use `record` for any immutable value object with 1–5 fields. Use a hand-written `final class` only when:

- The type holds an array field whose `equals` / `hashCode` need explicit `Arrays.equals` / `Arrays.hashCode` (records use reference equality for array fields, which is wrong for byte-array value types). Exception: `Bytes` is a `final class`.
- The type needs lazy initialisation or computed-once-cached fields.
- The type needs an explicit copy semantics on construction (the canonical-record constructor handles validation but defensive-copying mutable inputs is awkward in a record).

Validation goes in the **canonical (compact) record constructor**:

```java
public record ObjectId(String value) {
    public ObjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("ObjectId cannot be blank");
    }
}
```

Static factory methods use the `of(...)` convention for ergonomics.

## Alternatives considered

**Hand-written final classes for everything.** ~3-5× more code per type. Pre-Java-14 standard. The bug class "I forgot to override equals" remains. Net negative.

**Lombok.** Removes the boilerplate via annotations. Adds a build-time dependency that requires IDE plugin support. Java records do the same thing with no dependency.

**Project Manifold or Auto-Value.** Similar to Lombok. Same dependency cost.

**Use raw tuples / Map<String, Object>.** Too dynamic; loses the type safety that makes the rest of the codebase navigable.

## Consequences

**Positive:**
- ~30 types written in 3-5 lines each. The repo is small and readable.
- `equals` / `hashCode` correctness is free for every record.
- Pattern matching in switch statements (Java 21+) and `instanceof` patterns (Java 17+) work natively on records.
- Records imply immutability — readers know the value won't change.

**Negative:**
- Records can't extend other records; the type hierarchy is flat. This is fine for the repo but limits inheritance-heavy designs.
- The canonical-constructor parameter validation pattern is JDK-17-style; older Java codebases will look different.
- Some IDE refactorings (e.g. "convert to builder") are less ergonomic for records.

## Examples in the repo

| Record type | Module | Fields |
|---|---|---|
| `ObjectId(String value)` | `dfs-common` | 1 |
| `ChunkId(long value)` | `dfs-common` | 1 |
| `ReplicaId(ChunkId chunkId, OsdId osdId)` | `dfs-common` | 2 |
| `Range(long start, long length)` | `dfs-allocator` | 2 |
| `PgLocation(Generation generation, List<OsdId> osds)` | `dfs-placement` | 2 |
| `ChunkLease(ChunkId chunk, OsdId primaryOsd, Instant expiresAt)` | `dfs-lease` | 3 |
| `Extent(String extentId, ExtentStatus status, long length)` | `dfs-lease` | 3 |
| `Inode(long inodeId, long parentInodeId, String name, InodeType type)` | `dfs-mds` | 4 |
| `WorkItem(PgId pg, PriorityClass priority, String reason)` | `dfs-custodian` | 3 |
| `DurabilityEvent(PgId pg, int currentReplicas, int floor)` | `dfs-monitor` | 3 |
| `PutResult(PgId pg, List<OsdId> osds, ChunkId chunk, String extentId, int writtenBytes)` | `dfs-node` | 5 (max) |

`Bytes` in `dfs-common` is the one exception: it's a `final class` because its byte-array field needs `Arrays.equals` rather than reference equality.

## Implementation pointers

- The pattern is uniform across all modules. Search for `public record` in `src/main/java/**/*.java`.
- Style guide: validation in compact constructor; static `of(...)` factory; no setter (records are immutable by definition).

## Related

- [`modules/dfs-common.md`](../modules/dfs-common.md) — where most value-object decisions live
- [ADR-0001](./0001-pure-java-no-jni.md) — bounding the language version to Java 17
- [JEP 395 — Records](https://openjdk.org/jeps/395)
