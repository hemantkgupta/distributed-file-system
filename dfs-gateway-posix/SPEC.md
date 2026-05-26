# dfs-gateway-posix — LLM Implementation Spec

> **Status:** SPEC. No code yet. Generate against this; tick off the checklist in §11 as code lands.
>
> **Maps to:** §8 POSIX Gateway in the [full essay](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system/distributed-file-system-full.md#8-posix-gateway-nfs--smb--csi-driver) and the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#8-posix-gateway-nfs--smb--csi-driver).
>
> **Initial-pass scope:** **NFSv3** (RFC 1813) as the primary protocol head and a minimal Kubernetes **CSI** head as an optional secondary. **SMB is out of scope** for the initial pass — oplocks, named streams, change-notify, and lease state machines are too rich for a first cut and warrant their own SPEC later. NFSv4 delegations and RPCSEC_GSS are also out of scope; see §9.

---

## 1. Purpose

Expose the DFS cluster to applications that cannot or will not link the thick client library (§6). POSIX semantics in via NFS / CSI; internal MDS+OSD operations out via the client library. The gateway pays the context-switch and protocol-state cost the thick client avoids — in exchange, legacy workloads work unmodified.

The gateway is **a stateful protocol head with a translation layer underneath it**. State at the protocol layer (NFS file handles, NFS duplicate-reply cache, advisory lock table, CSI volume registry) lives in the gateway. State for byte durability, namespace, and metadata coherence lives in the cluster — every read or write of cluster state goes through the client library.

The two heads share a single internal `PosixGateway` surface so they can coexist in one JVM and so future heads (SMB, NFSv4) can plug in without duplicating the translation layer.

---

## 2. Position in the system

- **Upstream consumers:**
  - **NFSv3 clients** — typically the in-kernel NFS client on a Linux host mounting an export.
  - **Kubernetes** — kubelet on each node, talking to the CSI head over gRPC via a Unix domain socket.
- **Downstream dependencies:**
  - **§6 Client Library** (`dfs-client`, mock in the initial pass — see §10).
  - **`dfs-common`** for shared value types (paths, inode ids, time).
  - **`dfs-security`** (optional, future) for Kerberos / RPCSEC_GSS once it lands.
- **Sibling coordination:**
  - The MDS owns the durable inode↔path mapping. The gateway's `FileHandle` table is a *cache* of `(inode, generation)` keyed by an opaque 64-byte NFS handle; on cache miss, the gateway re-resolves through the client library.
  - The Custodian (§5) periodically scans for stale gateway-side state (orphan NFS handle generations, abandoned CSI mounts). The gateway only emits the records; it does not run the GC.

---

## 3. Public API surface

### 3.1 Internal unified surface

Both protocol heads call into a single Java interface. This is the seam that lets us add SMB / NFSv4 later without re-translating to the client library twice.

```java
package com.hkg.dfs.gateway.posix;

/** Unified POSIX surface that protocol heads (NFS, CSI, future SMB) call into. */
public interface PosixGateway {
    FileHandle  lookup   (FileHandle dir, String name);             // throws NoSuchFileException
    Attributes  getattr  (FileHandle fh);
    int         read     (FileHandle fh, long offset, byte[] buf);  // returns bytes read
    void        write    (FileHandle fh, long offset, byte[] data, boolean stable);
    FileHandle  create   (FileHandle dir, String name, Attributes initial);
    void        remove   (FileHandle dir, String name);             // unlink file
    void        rename   (FileHandle srcDir, String srcName, FileHandle dstDir, String dstName);
    FileHandle  mkdir    (FileHandle dir, String name, Attributes initial);
    void        rmdir    (FileHandle dir, String name);             // must be empty
    List<DirEntry> readdir (FileHandle dir, long cookie, int maxBytes);
    void        commit   (FileHandle fh, long offset, long count);  // fsync range

    LockGrant   lock     (FileHandle fh, LockRange range, LockOwner owner, LockType type);
    void        unlock   (FileHandle fh, LockRange range, LockOwner owner);
}
```

`PosixGatewayImpl` is the concrete class wiring `PosixGateway` to a `ClientLibrary` instance (real in production, mock in tests). It owns the `FileHandleTable`, the duplicate-reply cache (DRC), and the advisory lock manager.

### 3.2 NFSv3 head

```java
package com.hkg.dfs.gateway.posix.nfs;

/** NFSv3 RPC server. RFC 1813 program 100003, version 3. */
public final class NfsHead {
    public static NfsHead start(int port, PosixGateway gw, NfsConfig cfg);
    public int port();
    public void stop();
}

public record NfsConfig(
    int           port,                  // 2049 by default
    List<Export>  exports,               // path → access policy
    AuthMode      authMode,              // AUTH_SYS for initial pass
    Duration      drcWindow,             // how long the duplicate-reply cache holds entries
    int           drcMaxEntries
) {}

public record Export(String path, FileHandle rootHandle, AccessPolicy policy) {}
public enum AuthMode { AUTH_NONE, AUTH_SYS /* future: RPCSEC_GSS */ }
```

The NFSv3 procedures the head implements (RFC 1813 §3.3). Each procedure has a request and response record; bodies are XDR-encoded on the wire by the transport layer (see §10) and decoded into these records before dispatch.

```java
// NFS3PROC_NULL   (0)  — no-op, used for client probes
// NFS3PROC_GETATTR(1)
public record GetAttrArgs   (FileHandle fh) {}
public record GetAttrReply  (NfsStatus status, Attributes attrs) {}

// NFS3PROC_LOOKUP (3)
public record LookupArgs    (FileHandle dir, String name) {}
public record LookupReply   (NfsStatus status, FileHandle fh, Attributes attrs, Attributes dirAttrs) {}

// NFS3PROC_READ   (6)
public record ReadArgs      (FileHandle fh, long offset, int count) {}
public record ReadReply     (NfsStatus status, Attributes attrs, int count, boolean eof, byte[] data) {}

// NFS3PROC_WRITE  (7)
public record WriteArgs     (FileHandle fh, long offset, int count, StableHow stable, byte[] data) {}
public record WriteReply    (NfsStatus status, int count, StableHow committed, long verf) {}
public enum   StableHow     { UNSTABLE, DATA_SYNC, FILE_SYNC }

// NFS3PROC_CREATE (8)
public record CreateArgs    (FileHandle dir, String name, CreateMode mode, Attributes initial) {}
public record CreateReply   (NfsStatus status, FileHandle fh, Attributes attrs) {}
public enum   CreateMode    { UNCHECKED, GUARDED, EXCLUSIVE }

// NFS3PROC_MKDIR  (9)
public record MkdirArgs     (FileHandle dir, String name, Attributes initial) {}
public record MkdirReply    (NfsStatus status, FileHandle fh, Attributes attrs) {}

// NFS3PROC_REMOVE (12)
public record RemoveArgs    (FileHandle dir, String name) {}
public record RemoveReply   (NfsStatus status, Attributes dirAttrs) {}

// NFS3PROC_RMDIR  (13)
public record RmdirArgs     (FileHandle dir, String name) {}
public record RmdirReply    (NfsStatus status, Attributes dirAttrs) {}

// NFS3PROC_RENAME (14)
public record RenameArgs    (FileHandle srcDir, String srcName, FileHandle dstDir, String dstName) {}
public record RenameReply   (NfsStatus status, Attributes srcDirAttrs, Attributes dstDirAttrs) {}

// NFS3PROC_READDIR(16) and READDIRPLUS (17 — initial pass implements READDIR only)
public record ReaddirArgs   (FileHandle dir, long cookie, long cookieVerf, int count) {}
public record ReaddirReply  (NfsStatus status, long cookieVerf, List<DirEntry> entries, boolean eof) {}

// NFS3PROC_COMMIT (21)
public record CommitArgs    (FileHandle fh, long offset, int count) {}
public record CommitReply   (NfsStatus status, long verf) {}
```

`NfsStatus` enumerates the RFC 1813 status codes (`NFS3_OK`, `NFS3ERR_NOENT`, `NFS3ERR_STALE`, `NFS3ERR_IO`, `NFS3ERR_NOTEMPTY`, `NFS3ERR_ACCES`, `NFS3ERR_JUKEBOX`, etc.).

The companion **NLM (Network Lock Manager) v4** sidecar carries advisory locks; initial pass exposes `NLM4_LOCK` and `NLM4_UNLOCK` only (see §9).

### 3.3 CSI head (optional secondary)

```java
package com.hkg.dfs.gateway.posix.csi;

/** Kubernetes CSI gRPC server, served on a Unix domain socket. */
public final class CsiHead {
    public static CsiHead start(Path socketPath, PosixGateway gw, CsiConfig cfg);
    public Path socketPath();
    public void stop();

    // CSI v1.x — Controller plugin
    CreateVolumeResponse        createVolume        (CreateVolumeRequest req);
    DeleteVolumeResponse        deleteVolume        (DeleteVolumeRequest req);
    ControllerPublishVolumeResponse   controllerPublishVolume   (ControllerPublishVolumeRequest req);
    ControllerUnpublishVolumeResponse controllerUnpublishVolume (ControllerUnpublishVolumeRequest req);

    // CSI v1.x — Node plugin
    NodeStageVolumeResponse     nodeStageVolume     (NodeStageVolumeRequest req);
    NodeUnstageVolumeResponse   nodeUnstageVolume   (NodeUnstageVolumeRequest req);
    NodePublishVolumeResponse   nodePublishVolume   (NodePublishVolumeRequest req);
    NodeUnpublishVolumeResponse nodeUnpublishVolume (NodeUnpublishVolumeRequest req);
}
```

Each CSI request / response is a Java record mirroring the CSI gRPC proto messages, fields trimmed to what the initial pass uses (`volume_id`, `capacity_bytes`, `volume_capabilities`, `target_path`).

---

## 4. Data model

### 4.1 FileHandle

```java
/** Opaque 64-byte handle the NFS client treats as a cookie. */
public record FileHandle(byte[] bytes) {
    public static final int LENGTH = 64;
    public Inode inode();           // decoded from bytes[0..8)
    public long  generation();      // decoded from bytes[8..16); MDS-issued
    public byte[] exportTag();      // decoded from bytes[16..24); identifies the export
}

/** In-memory cache of recently-resolved handles. Cold misses re-resolve via MDS. */
public final class FileHandleTable {
    public FileHandle issue(Inode inode, byte[] exportTag);  // encodes + caches
    public Inode      resolve(FileHandle fh);                // throws StaleHandleException
    public void       invalidate(Inode inode);               // on remove / rename
}
```

The generation field is the MDS-issued inode generation number. A handle whose generation no longer matches the MDS record decodes to `NFS3ERR_STALE` — this is how NFS handles renames-into-a-different-inode and inode reuse cleanly.

### 4.2 Attributes

```java
public record Attributes(
    FileType type,
    int      mode,        // POSIX permission bits
    int      uid,
    int      gid,
    long     nlink,
    long     size,
    long     usedBytes,
    long     fsid,
    long     fileid,      // inode id
    Instant  atime,       // initial pass: 1-second granularity
    Instant  mtime,       // initial pass: 1-second granularity
    Instant  ctime
) {}
public enum FileType { REG, DIR, SYMLINK, BLK, CHR, FIFO, SOCK }
```

Atime / mtime / ctime resolution in the initial pass is **seconds**, not nanoseconds. The MDS internally tracks finer granularity but NFSv3 sends seconds-and-nanoseconds and we truncate the nanos to zero for now (see §9).

### 4.3 LockState

```java
public record LockOwner(byte[] ownerBytes /* client-supplied opaque */) {}
public record LockRange(long offset, long length, boolean wholeFile) {}
public enum   LockType { READ, WRITE, UNLOCK }
public record LockGrant(boolean granted, LockOwner conflictingOwner /* null on grant */) {}

public record LockState(
    Inode      inode,
    List<HeldLock> heldLocks
) {}
public record HeldLock(LockOwner owner, LockRange range, LockType type) {}
```

NFSv3 locks are **advisory** — the kernel never blocks a non-cooperating reader/writer; the lock manager just records the lock and reports conflicts to other lock requesters. Initial-pass implementation: an in-memory `Map<Inode, LockState>` in the gateway, no persistence. A crash drops all locks (NFS clients are expected to re-acquire on reclaim — that mechanism is stubbed; see §9).

### 4.4 CSI Volume

```java
public record Volume(
    String   volumeId,         // gateway-issued; opaque to k8s
    long     rootInode,        // backing inode in the cluster
    long     capacityBytes,
    Set<AccessMode> modes,     // SINGLE_NODE_WRITER, MULTI_NODE_READER_ONLY, etc.
    Map<String, String> parameters,
    Instant  createdAt
) {}
public enum AccessMode { SINGLE_NODE_WRITER, SINGLE_NODE_READER_ONLY, MULTI_NODE_READER_ONLY, MULTI_NODE_MULTI_WRITER }
```

Volume records are persisted as files under `/{cluster}/_csi/{volumeId}.json` via the client library — same convention the S3 gateway uses for bucket records.

### 4.5 Duplicate-Reply Cache (DRC) entry

```java
public record DrcEntry(
    long    xid,           // RPC transaction id
    int     procedure,     // NFS3PROC_*
    byte[]  argChecksum,   // hash of arg bytes for collision detection
    byte[]  cachedReply,   // XDR-encoded reply
    Instant insertedAt
) {}
```

Keyed by `(client_address, xid)`. TTL governed by `NfsConfig.drcWindow` (default: 2 minutes — long enough to absorb any reasonable retry storm without growing unboundedly).

---

## 5. Life of a request

### 5.1 NFS open (LOOKUP + GETATTR)

```
NFS client kernel → NfsHead (XDR-decode) → PosixGateway → ClientLibrary → MDS / OSDs
```

1. Application calls `open("/mnt/dfs/file.bin", O_RDONLY)`. The kernel NFS client walks the path one component at a time, issuing one `LOOKUP` RPC per component.
2. For `LOOKUP("file.bin")` against the parent directory handle:
   a. The transport layer decodes the XDR-encoded `LookupArgs` and dispatches by procedure number.
   b. `NfsHead` first checks the DRC: if `(client_addr, xid)` is present and the cached arg checksum matches, return the cached reply immediately. Otherwise proceed.
   c. `NfsHead` resolves the `dir` handle via `FileHandleTable.resolve(dir)` → `Inode dirInode`.
   d. `NfsHead` calls `gateway.lookup(dirHandle, "file.bin")`.
   e. `PosixGatewayImpl.lookup` calls `client.lookup(dirInode, "file.bin")` which talks to the MDS. MDS returns `(childInode, generation)` or `NoSuchFileException`.
   f. Gateway issues a fresh `FileHandle` via `FileHandleTable.issue(childInode, exportTag)`, fetches attributes via `client.getattr(childInode)`, returns `LookupReply(NFS3_OK, fh, attrs, dirAttrs)`.
   g. `NfsHead` stores the reply in the DRC and XDR-encodes it back to the wire.
3. The kernel NFS client then issues `GETATTR(fh)` immediately for the new handle:
   a. DRC check (almost certainly miss — different xid).
   b. `gateway.getattr(fh)` → `client.getattr(inode)` → MDS → `Attributes`.
   c. `GetAttrReply(NFS3_OK, attrs)` returned.
4. The kernel NFS client now has a file handle and attributes; the user-space `open(2)` returns. No data has been read yet.

There is no `open` RPC in NFSv3 — handles are stateless from the protocol's perspective. The gateway holds no per-open state either; it just looks up the inode each time a request arrives. This is exactly why NFSv3 is the simplest head to build.

### 5.2 NFS read (after open)

1. Application calls `read(fd, buf, 4096)`. Kernel NFS client issues `READ(fh, offset=0, count=4096)`.
2. NfsHead DRC check; if miss, dispatch to `gateway.read(fh, 0, buf)`.
3. `PosixGatewayImpl.read`:
   a. Resolves `fh` → `Inode inode`. If the handle is stale (generation mismatch), returns `NFS3ERR_STALE` immediately.
   b. Acquires a read capability from the MDS via the client library (cap-cached for subsequent reads of the same inode).
   c. Calls `client.read(inode, offset=0, buf)` which reads from the OSD(s) holding the relevant extent.
   d. Returns the number of bytes read.
4. NfsHead packages the bytes into `ReadReply(NFS3_OK, attrs, count, eof, data)`, stores in the DRC, XDR-encodes, sends.
5. If the file is larger than the response (NFS3 limits a single READ to ~1 MiB), the client kernel issues a second READ at the next offset. The gateway's read cap is reused from the cache; no MDS round-trip.

### 5.3 NFS write (UNSTABLE + COMMIT)

NFSv3 writes carry a stability hint: `UNSTABLE` means "you may buffer", `DATA_SYNC`/`FILE_SYNC` mean "fsync this before replying". Clients usually issue many `UNSTABLE` writes followed by one `COMMIT` per file.

1. Kernel NFS client issues `WRITE(fh, offset=N, count=4096, stable=UNSTABLE, data=...)`.
2. NfsHead → `gateway.write(fh, N, data, stable=false)`.
3. `PosixGatewayImpl.write` acquires a write cap (cached after first), writes via the client library (which may buffer), returns. Reply carries `committed=UNSTABLE` and a `verf` (write verifier — a token that changes on gateway restart so clients know their UNSTABLE writes were lost).
4. After the application calls `fsync()`, the kernel NFS client issues `COMMIT(fh, offset=0, count=0)`.
5. NfsHead → `gateway.commit(fh, 0, 0)` → `client.fsync(inode)` which makes the OSD durably commit and the MDS commit any new metadata.
6. Reply carries `verf` — same as the writes' verf if the gateway didn't restart in between. If the verf differs, the client re-sends the unstable writes (this is how NFSv3 survives a gateway restart without data loss, *provided* the client hasn't released its dirty pages).

---

## 6. Invariants the implementation must hold

After `LOOKUP(dir, name)` returns `NFS3_OK` with handle `fh`:
- `GETATTR(fh)` succeeds and returns `attrs.fileid` equal to the inode the lookup resolved.
- The handle remains valid until the inode is unlinked AND its generation increments.

After `WRITE(fh, off, data, FILE_SYNC)` returns `NFS3_OK`:
- The bytes are durable. A subsequent `READ(fh, off, len(data))` on a healthy cluster returns exactly `data`.
- `GETATTR(fh).mtime` is no earlier than the moment the WRITE began.

After `COMMIT(fh)` returns `NFS3_OK` with verf `V`:
- All `UNSTABLE` writes accepted before this commit with the same verf `V` are durable.
- If a subsequent operation returns a verf `V' != V`, the gateway has restarted and the client must re-send any not-yet-committed writes.

After `REMOVE(dir, name)` returns `NFS3_OK`:
- Any further use of a handle previously resolving to that name returns `NFS3ERR_STALE`.
- `LOOKUP(dir, name)` returns `NFS3ERR_NOENT`.

After `RENAME(srcDir, srcName, dstDir, dstName)` returns `NFS3_OK`:
- `LOOKUP(srcDir, srcName)` returns `NFS3ERR_NOENT`.
- `LOOKUP(dstDir, dstName)` returns the handle that previously belonged to `srcName`; the inode is unchanged (so the file handle a client opened before the rename keeps working).

The DRC must hold each `(client_addr, xid)` for at least `NfsConfig.drcWindow`. A retried RPC with the same `(addr, xid)` must return the cached reply byte-for-byte — never re-execute the side effect.

The gateway must **never crash on malformed XDR**. Every decode error routes to `NFS3ERR_IO` (or a transport-layer reject for unparseable RPC framing).

---

## 7. Failure modes & required handling

| Trigger | Status | Notes |
|---|---|---|
| Stale handle (inode unlinked or generation mismatch) | `NFS3ERR_STALE` | the canonical NFS error; clients re-resolve via path |
| Lookup of missing name | `NFS3ERR_NOENT` | |
| Permission denied (AUTH_SYS uid/gid check fails) | `NFS3ERR_ACCES` | |
| Directory not empty (RMDIR) | `NFS3ERR_NOTEMPTY` | |
| Client library throws (MDS or OSD unreachable) | `NFS3ERR_JUKEBOX` | "try again later" — kernel NFS client backs off and retries; gateway logs |
| Decode failure (malformed XDR) | `NFS3ERR_IO` | also increments a decode-error metric |
| Duplicate RPC (same `(addr, xid)`) within DRC window | cached reply | never re-execute |
| Duplicate RPC after DRC eviction | re-execute | this is the unavoidable correctness hazard NFS lives with — see below |
| Lock conflict on NLM4_LOCK | `NLM4_DENIED` | with conflicting-owner echoed back |
| Advisory lock requested against an inode with an outstanding MDS write cap held by another client | conservatively grant the lock | log a metric; lock-vs-cap divergence is a known accept-the-mismatch case (see below) |

**NFS retry semantics.** Kernel NFS clients retry timed-out RPCs **forever** — there's no abort. Combined with non-idempotent operations (`CREATE` with `GUARDED`, `REMOVE`, `RENAME`), this would cause silent corruption without the DRC: client sends `REMOVE`, gateway succeeds, reply lost, client retries; without DRC the gateway returns `NFS3ERR_NOENT` and the client thinks the unlink failed. The DRC pins the reply for `drcWindow` so the retry sees the same `NFS3_OK`. Beyond the window, the failure mode reappears — which is why the window has to be long enough to outlast plausible network partitions but not so long it pins unbounded memory.

**Lock-semantics mismatch.** NFSv3 advisory locks (via NLM4) are byte-range, owner-keyed, and have no relationship to opens. The MDS's exclusive write capability is per-inode, capability-keyed, and tightly coupled to opens. They do not map. The initial pass keeps the advisory lock table entirely *in the gateway* and never asks the MDS to enforce it — workloads that rely on lock-meets-cap semantics (typically databases over NFS, which we discourage anyway) accept relaxed semantics in exchange for protocol compatibility.

**Negative-readdir cache.** NFS clients cache "name not found" results for `acdirmin` to `acdirmax` seconds (default 30–60s on Linux). If a file is created on another mount during that window, this mount's client returns `ENOENT` for it. The gateway cannot fix this — it's a kernel-side cache the gateway never sees. Mitigation: document the symptom in the operations runbook; advise tuning `acdirmin=1` for mounts that need lower latency on cross-client visibility.

---

## 8. Testing acceptance criteria

Required tests in `dfs-gateway-posix/src/test/java/com/hkg/dfs/gateway/posix/`. Tests run against `PosixGatewayImpl` wired to an in-memory `ClientLibrary` mock (same mock pattern as `dfs-gateway-s3`) — no real network, no real disk.

For the NFS head we also test the XDR-decoded request path: tests construct `LookupArgs` / `ReadArgs` / etc. directly and dispatch into `NfsHead.handle(...)`, bypassing the socket layer. The socket layer gets its own minimal integration test that confirms framing round-trips.

| Test class | Test method | Asserts |
|---|---|---|
| `PosixGatewayLookupTest` | `lookupExistingFileReturnsHandle` | After `create("file"); lookup("file")` → NFS3_OK with a non-null handle |
| `PosixGatewayLookupTest` | `lookupMissingReturnsNoent` | `lookup("missing")` → NFS3ERR_NOENT |
| `PosixGatewayLookupTest` | `staleHandleAfterRemoveReturnsStale` | Create, lookup (capture handle), remove, `getattr(handle)` → NFS3ERR_STALE |
| `PosixGatewayReadWriteTest` | `writeFileSyncThenReadRoundTrip` | WRITE 4 KiB FILE_SYNC, READ same range, bytes identical |
| `PosixGatewayReadWriteTest` | `unstableWriteThenCommit` | WRITE UNSTABLE × 4, COMMIT, READ — bytes identical; verf matches |
| `PosixGatewayReadWriteTest` | `verfChangesAcrossRestart` | WRITE UNSTABLE, restart gateway, second WRITE — verf differs |
| `PosixGatewayCreateRemoveTest` | `createThenRemoveThenLookupNoent` | Create file, remove, lookup → NFS3ERR_NOENT |
| `PosixGatewayCreateRemoveTest` | `removeNonexistentReturnsNoent` | Remove of unknown name → NFS3ERR_NOENT |
| `PosixGatewayDirTest` | `mkdirRmdirRoundTrip` | mkdir, lookup, rmdir, lookup → NFS3ERR_NOENT |
| `PosixGatewayDirTest` | `rmdirOnNonemptyReturnsNotempty` | mkdir, create file in it, rmdir → NFS3ERR_NOTEMPTY |
| `PosixGatewayDirTest` | `readdirReturnsAllEntries` | Create 5 files, readdir, entries contain all 5 names |
| `PosixGatewayDirTest` | `readdirPagination` | Create 200 files, readdir with small `count`, multiple pages reassemble to full set, no duplicates |
| `PosixGatewayRenameTest` | `renamePreservesInode` | Create, capture inode via getattr, rename, lookup new name → same inode |
| `PosixGatewayRenameTest` | `renameOverExistingTarget` | Rename A→B where B exists; B's old inode is unlinked; new lookup of B yields A's inode |
| `PosixGatewayLockTest` | `writeLockBlocksConflictingOwner` | Owner1 acquires WRITE on [0,100); Owner2 attempts WRITE on [50,200) → DENIED with Owner1 echoed |
| `PosixGatewayLockTest` | `unlockReleases` | Lock, unlock, second lock by different owner succeeds |
| `NfsDrcTest` | `duplicateRpcReturnsCachedReply` | REMOVE with xid=X succeeds; resending same `(addr, xid)` → cached reply; underlying inode is unlinked once |
| `NfsDrcTest` | `drcEvictsAfterWindow` | After `drcWindow` elapses, the same `(addr, xid)` re-executes |
| `NfsXdrFramingTest` | `roundTripGetAttr` | XDR-encode a GETATTR call, decode it back, args equal |
| `CsiBasicTest` | `createVolumeThenNodePublish` | CreateVolume → NodePublishVolume → target path exists in gateway state |
| `CsiBasicTest` | `deleteVolumeRemovesRecord` | After DeleteVolume, the volume record is gone and a fresh NodePublish on the same id fails |

All tests pass under `./gradlew :dfs-gateway-posix:test`.

---

## 9. Stubs allowed / out of scope (initial pass)

- **SMB head.** Out of scope entirely. The protocol's lease state machine, oplocks, named streams, change-notify, and ACL model need their own SPEC and deserve their own module (`dfs-gateway-smb`).
- **NFSv4 / delegations.** The initial pass is **NFSv3 only**. NFSv4's state model (open/lock state IDs, delegations, callbacks, sessions, pNFS) is incompatible with v3's stateless handle model and warrants a separate head.
- **RPCSEC_GSS / Kerberos.** Auth is **AUTH_SYS** only — the client's claimed uid/gid is trusted on the wire. This is the standard "trusted network" NFS deployment mode. Real RPCSEC_GSS lands once `dfs-security` exposes Kerberos primitives.
- **NLM async grant callbacks and reclaim.** `NLM4_LOCK` and `NLM4_UNLOCK` are synchronous in the initial pass. `NLM4_TEST`, async grant on a deferred lock, and the lock-reclaim grace period after a gateway restart are stubbed — restart drops all advisory locks; clients re-acquire on next operation.
- **READDIRPLUS.** Initial pass implements `READDIR` only. `READDIRPLUS` (returning attrs for each entry in one RPC) is a perf optimization, not required for correctness.
- **Symlinks, special files, hard links.** `SYMLINK`, `MKNOD`, `LINK`, `READLINK` procedures return `NFS3ERR_NOTSUPP` initially. Add when the underlying MDS exposes the operations.
- **POSIX attribute fidelity.** Atime / mtime / ctime granularity is **seconds**, not nanoseconds — the client library will return nanosecond-resolution timestamps but the gateway truncates the nanos field to 0 when encoding NFS replies. Mode bits, uid, gid round-trip faithfully; ACLs (`NFSACL`) are not exposed.
- **Mount protocol.** RFC 1813 §4 (MOUNT v3) is a separate program. Initial pass stubs the mount path by configuring exports statically in `NfsConfig`; real-world deployments would also need the MOUNT program to dynamically issue root handles. Lands as a small follow-up once NFS3 procedures work.
- **CSI head.** Implemented to a minimum viable surface for a k8s cluster to mount a DFS volume — full snapshot/clone/expand operations are stubbed. SMB-equivalent volume modes (`MULTI_NODE_MULTI_WRITER` for non-POSIX-coherent workloads) advertise the capability but rely on the same NFS semantics underneath.
- **Network transport.** See §10 — for the initial pass the recommended path is to **not** ship a full XDR/ONC-RPC socket server, and instead unit-test `NfsHead.handle(args)` directly against constructed records. A minimal hand-rolled XDR encoder is sufficient if a real socket server is desired later.

---

## 10. Dependencies

### Build

```groovy
// In root build.gradle, add:
project(':dfs-gateway-posix') {
    dependencies {
        api project(':dfs-common')
        api project(':dfs-client')             // §6 — mock for initial pass; see below
        implementation project(':dfs-security')    // optional; future Kerberos / RPCSEC_GSS

        implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'

        // gRPC for CSI head — only if CSI is being implemented this pass
        implementation 'io.grpc:grpc-netty-shaded:1.62.2'
        implementation 'io.grpc:grpc-protobuf:1.62.2'
        implementation 'io.grpc:grpc-stub:1.62.2'

        testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    }
}
```

### Runtime

- JDK 17+.
- Jackson for JSON serialization of gateway-owned records (CSI volumes).
- gRPC for the CSI head (Unix domain socket transport).
- **No XDR / ONC-RPC library.** See the next section.

### XDR / ONC-RPC transport decision

NFSv3 is layered on Sun-RPC over UDP or TCP, with arguments XDR-encoded. Two options:

1. **Drop in a heavy dependency** — projects like `jrpcgen` / `oncrpc4j` exist but pull in a large surface area, and most haven't been actively maintained for years.
2. **Hand-roll a minimal XDR encoder** — XDR is genuinely small (uint32 big-endian, length-prefixed opaque bytes, length-prefixed strings, variable-length arrays). ~300 lines of Java covers everything NFS3PROC procedures need.
3. **Skip the socket layer entirely for the initial pass** — define `NfsHead.handle(NfsCall) → NfsReply` and unit-test it against constructed `*Args` records. The socket-and-XDR layer becomes a follow-up that wraps this in-process interface.

**Recommendation: option 3 for the initial pass, with the option to add a minimal hand-rolled XDR encoder when a real Linux mount is wanted.** The point of this module is the *translation logic* (POSIX semantics ↔ client library, DRC, locks, handles); the wire format is plumbing. Treating it as plumbing keeps the first cut focused.

### Mock client library (since §6 doesn't exist yet)

This SPEC assumes the same `ClientLibrary` interface that `dfs-gateway-s3/SPEC.md` introduces. The NFS / CSI translation needs a slightly richer surface than S3 — additional methods used here that the gateway calls:

```java
public interface ClientLibrary {
    // Methods §7 already uses
    long create(String path);
    long open(String path);
    void write(long inode, long offset, byte[] bytes);
    int  read (long inode, long offset, byte[] buf);
    long size (long inode);
    void close(long inode);
    void unlink(String path);
    void mkdir(String path);
    List<String> readdir(String path);
    boolean exists(String path);

    // Additional surface needed by the POSIX gateway
    long lookup(long parentInode, String name);     // returns child inode; throws NoSuchFileException
    Attributes getattr(long inode);
    long generation(long inode);                    // for stale-handle detection
    void rename(long srcDir, String srcName, long dstDir, String dstName);
    void rmdir(long inode);                         // throws DirectoryNotEmptyException
    void fsync(long inode);                         // explicit; close() implicitly fsyncs
}
```

Implement `InMemoryClientLibrary implements ClientLibrary` in this module's test source set. When §6 lands, the gateway depends on the real one and the mock stays in tests. The §6 SPEC (being written in parallel) should treat the methods above as required surface.

### Wiki concepts implemented

- [`nfs-file-handle`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/nfs-file-handle.md) *(to be written)*
- [`duplicate-reply-cache`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/duplicate-reply-cache.md) *(to be written)*
- [`advisory-locking`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/advisory-locking.md) *(to be written)*

### Essay section

[§8. POSIX Gateway (NFS / SMB / CSI Driver)](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system/distributed-file-system-full.md#8-posix-gateway-nfs--smb--csi-driver)

---

## 11. Implementation checklist

Roughly in order. Each item should land with its tests.

**Foundations**
- [ ] `PosixGatewayConfig` (exports, drc tuning, csi socket path)
- [ ] `FileHandle`, `Attributes`, `LockOwner`, `LockRange`, `HeldLock`, `LockState` records
- [ ] `FileHandleTable` with issue / resolve / invalidate
- [ ] `PosixGateway` interface + `PosixGatewayImpl` skeleton
- [ ] Mock `InMemoryClientLibrary` in `src/test/java/.../testutil/` (extended surface — see §10)
- [ ] Advisory lock manager (in-memory `Map<Inode, LockState>`) + `PosixGatewayLockTest`

**NFSv3 head — per RPC procedure**
- [ ] `NfsHead` skeleton + `NfsConfig`
- [ ] DRC implementation + `NfsDrcTest.duplicateRpcReturnsCachedReply` + `drcEvictsAfterWindow`
- [ ] `NFS3PROC_GETATTR` + tests
- [ ] `NFS3PROC_LOOKUP` + `PosixGatewayLookupTest.*`
- [ ] `NFS3PROC_READ` + `PosixGatewayReadWriteTest.writeFileSyncThenReadRoundTrip`
- [ ] `NFS3PROC_WRITE` (UNSTABLE + DATA_SYNC + FILE_SYNC) + `unstableWriteThenCommit` + `verfChangesAcrossRestart`
- [ ] `NFS3PROC_COMMIT` (covered by tests above)
- [ ] `NFS3PROC_CREATE` (UNCHECKED + GUARDED + EXCLUSIVE modes) + `PosixGatewayCreateRemoveTest.createThenRemoveThenLookupNoent`
- [ ] `NFS3PROC_REMOVE` + `removeNonexistentReturnsNoent`
- [ ] `NFS3PROC_MKDIR` + `PosixGatewayDirTest.mkdirRmdirRoundTrip`
- [ ] `NFS3PROC_RMDIR` + `rmdirOnNonemptyReturnsNotempty`
- [ ] `NFS3PROC_RENAME` + `PosixGatewayRenameTest.renamePreservesInode` + `renameOverExistingTarget`
- [ ] `NFS3PROC_READDIR` + `readdirReturnsAllEntries` + `readdirPagination`
- [ ] NLM4 sidecar: `NLM4_LOCK` + `NLM4_UNLOCK` (covered by `PosixGatewayLockTest`)
- [ ] *(Optional)* Hand-rolled XDR encoder + `NfsXdrFramingTest.roundTripGetAttr` + socket layer

**CSI head (optional secondary)**
- [ ] `CsiHead` skeleton + gRPC server on UDS
- [ ] `CreateVolume` + `DeleteVolume` + `CsiBasicTest.deleteVolumeRemovesRecord`
- [ ] `NodePublishVolume` + `NodeUnpublishVolume` + `CsiBasicTest.createVolumeThenNodePublish`
- [ ] *(Optional)* `ControllerPublishVolume` / `ControllerUnpublishVolume`
- [ ] *(Optional)* `NodeStageVolume` / `NodeUnstageVolume`

**Wiring**
- [ ] Add `':dfs-gateway-posix'` to `settings.gradle`
- [ ] Add dependency block to root `build.gradle`
- [ ] All tests pass under `./gradlew :dfs-gateway-posix:test`

**Documentation**
- [ ] Write `docs/modules/dfs-gateway-posix.md` in the existing 7-section format (Role → Wiki anchor → Public API surface → Internal structure → Key tests → Where it fits → Stubs and departures from production)
- [ ] Add `dfs-gateway-posix` row to `docs/modules/README.md` index
- [ ] Update [`wiki/my-explanations/distributed-file-system-services.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md) §8 status from ❌ Missing → ✅ Implemented (NFSv3 + CSI; SMB still out of scope)

When all boxes are ticked, this SPEC.md can be moved to `docs/specs/dfs-gateway-posix.md` for historical reference. The `docs/modules/dfs-gateway-posix.md` doc becomes the contract going forward.
