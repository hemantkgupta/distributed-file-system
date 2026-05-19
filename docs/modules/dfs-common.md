# dfs-common

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The repo's shared vocabulary. Every other module imports its identifier types. `dfs-common` owns the value-object alphabet — `ObjectId`, `ChunkId`, `PgId`, `OsdId`, `TenantId`, `Generation`, `ReplicaId`, `Bytes` — and nothing else. It deliberately has no architectural opinions; it's the layer where opinions can't yet exist.

## 2. Wiki anchor

No single wiki concept owns these types — they're the type alphabet of the design walkthrough at [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md).

## 3. Public API surface

All in package `com.hkg.dfs.common`. Eight types, each in its own file.

```java
public record ObjectId(String value) { ... static of(String) ... }
public record ChunkId(long value)    { ... static of(long)   ... }
public record PgId(int value)        { ... static of(int)    ... }
public record OsdId(int value)       { ... static of(int)    ... }
public record TenantId(String value) { ... static of(String) ... }

public record Generation(long value) {
    public Generation next();
    public static Generation initial();
}

public record ReplicaId(ChunkId chunkId, OsdId osdId) { ... }

public final class Bytes {
    public static Bytes copyOf(byte[] src);
    public byte[] toByteArray();
    public int length();
    public byte at(int i);
}
```

Source files:
- `dfs-common/src/main/java/com/hkg/dfs/common/ObjectId.java`
- `.../ChunkId.java`, `.../PgId.java`, `.../OsdId.java`, `.../TenantId.java`
- `.../Generation.java`, `.../ReplicaId.java`, `.../Bytes.java`

## 4. Internal structure

The module has no internal collaborators — each public type is a leaf. Two notable design choices:

- **`record` for value objects with 1–5 fields.** Java 17 records give `equals`/`hashCode`/`toString` for free. The bug class of "I forgot to override equals" disappears.
- **`Bytes` is `final class`, not a `record`.** Records' generated `equals`/`hashCode` use object identity on array fields. For a byte-array value type to behave correctly, you need explicit `Arrays.equals` / `Arrays.hashCode` — which means a hand-written class.

Validation is in the canonical (compact) record constructor:

```java
public record ObjectId(String value) {
    public ObjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("ObjectId cannot be blank");
    }
}
```

## 5. Key tests

22 tests in `dfs-common/src/test/`, one test class per type.

| Test | Demonstrates |
|---|---|
| `ObjectIdTest.rejectsBlank` / `rejectsNull` | Compact constructor's validation fires on bad input. |
| `BytesTest.copyIsDefensive` | Mutating the source array after `copyOf` does not affect the wrapper. |
| `BytesTest.toByteArrayIsDefensive` | Mutating the returned array does not affect the wrapper. |
| `GenerationTest.nextIncrements` | Monotonic progression of generation numbers. |
| `ReplicaIdTest.carriesChunkAndOsd` | Two `ReplicaId`s with the same chunk/osd compare equal. |

## 6. Where it fits

**Upstream consumers (everything):** `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node`, `dfs-storage`, `dfs-mds`, `dfs-monitor`, `dfs-custodian`, `dfs-security`, `dfs-simulator`.

**Downstream dependencies:** none. `dfs-common` only depends on the JDK.

**The rule this module enforces:** value-object semantics. If two `ObjectId`s have the same `value`, they are equal — across modules, across method boundaries, across threads.

## 7. Stubs and departures from production

This module is one of the few that's at production fidelity. A real cluster file system would add:

- **Cryptographic randomness for IDs** — UUIDs or 128-bit cluster-monotonic identifiers, not just opaque longs.
- **Tenant-scoped IDs** — production `ObjectId`s carry the tenant ID inline so cross-tenant collisions are structurally impossible.
- **Wire encodings** — production IDs serialize compactly (protobuf, binary). This module exposes pure Java records; serialization would be a separate module.

None of these gaps are load-bearing for the rest of the repo's teaching content.
