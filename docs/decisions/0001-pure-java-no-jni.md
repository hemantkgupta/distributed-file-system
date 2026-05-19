# ADR-0001: Pure-Java Implementations, No JNI

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

The wiki's architectural decisions for cluster file systems are heavily about low-level details: bitmap allocators on raw block devices, CRC32c per blob, AES-GCM at rest, Reed-Solomon over GF(2^8), vsock between host and microVM. Each of those, in a production implementation, sits on JNI-bound native libraries (Intel ISA-L for EC, OpenSSL for crypto, libcrc for CRC) — sometimes for performance reasons (SIMD-accelerated GF arithmetic), sometimes because the underlying syscall (`/dev/kvm`, `AF_VSOCK`) has no JDK API.

This repo is a teaching artifact. The audience reads the blog with the codebase open. Adding JNI dependencies — even ones that would more faithfully reflect production — means a reader has to install build toolchains, deal with cross-platform binary distribution, and accept that the JAR no longer Just Works on every platform.

## Decision

Implement every component in pure Java, using JDK-only APIs. No JNI, no native libraries, no build-time fetches of binary blobs. When the wiki's production technique demands a native primitive, use the closest pure-Java approximation and call out the gap explicitly in the module's "Stubs and departures from production" section.

## Alternatives considered

**Use Intel ISA-L bindings for erasure coding.** Would give true Galois-field Reed-Solomon at ~10 GB/s/core. The build cost is significant: per-platform native libraries, classpath setup. The teaching value is also limited — the wiki page covers RS theory; readers don't gain by reading a JNI shim.

**Use BouncyCastle or Apache Commons-Crypto for AES-GCM.** The JDK already has `Cipher.getInstance("AES/GCM/NoPadding")` since Java 8; we use it (see `dfs-security`). BouncyCastle would only matter for esoteric algorithms.

**Use FFM API (Java 21+) for native interop.** Cleaner than JNI; still needs native libraries on the path. Same overall complexity for the reader. Java 17 is the project's minimum.

**Embed RocksDB via the official jrocksdb JAR.** Adds 30+ MB to the classpath, requires per-platform native binaries packaged inside the JAR. The OSD's RocksDB use is well-described in the wiki; we approximate with `ConcurrentSkipListMap`.

## Consequences

**Positive:**
- The repo builds with just JDK 17 + Gradle. `./gradlew build` from a clean clone on macOS, Linux, Windows produces the same green test suite.
- No platform-specific code paths to maintain.
- The teaching content stays accessible — readers don't lose time on toolchain setup before reading the code.
- Tests are deterministic across platforms.

**Negative:**
- Performance numbers in this repo are not representative of production. The XOR-parity stub is not the same workload as Reed-Solomon over GF(2^8).
- Some wiki concepts simply can't be demonstrated end-to-end (e.g. the `/dev/kvm` ioctl path that production Firecracker uses).
- Readers who want to see "real" production code have to read Ceph or HDFS source directly.

## Implementation pointers

- AES-GCM via `javax.crypto.Cipher` in `dfs-security/.../Kms.java`.
- CRC32c via `java.util.zip.CRC32C` in `dfs-storage/.../Osd.java`.
- Bitmap manipulation in pure Java in `dfs-allocator/.../BitmapAllocator.java`.
- Locking via `synchronized` and `java.util.concurrent.*` everywhere; no native locks.

## Related

- [`getting-started.md`](../getting-started.md) — the "honesty about what this code is" section
- [ADR-0002](./0002-in-memory-substrates.md) — closely related decision on substrates
- [ADR-0003](./0003-xor-parity-stub-not-galois.md) — the specific EC stub choice
