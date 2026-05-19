# ADR-0005: Real AES-GCM Crypto, Not a Stub

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

Almost every component in this repo is stubbed (in-memory KV instead of RocksDB; XOR parity instead of true RS; one-process monitor instead of Paxos). The KMS is the exception. The decision was: should `dfs-security.Kms` use real AES-GCM, or stub the crypto?

The architectural pattern being demonstrated is **crypto-shredding for GDPR erasure**. The whole reason this pattern works is the cryptographic property: AES-GCM with a destroyed key is genuinely unrecoverable. A stub that just stored plaintext and pretended to "destroy a key" would teach the wrong lesson — a reader could rightly ask "what stops someone from re-reading the in-process map?" If the crypto is real, that question's answer is "key recovery is computationally infeasible".

## Decision

Use the JDK's built-in `Cipher.getInstance("AES/GCM/NoPadding")` with 128-bit keys, 96-bit random IVs, and 128-bit authentication tags. Generate keys via `KeyGenerator.getInstance("AES")` with `SecureRandom`. Store keys in a `ConcurrentHashMap<DukId, SecretKey>` (still in-memory, but the bytes inside the SecretKey are real cryptographic material). Destroy = `map.remove`.

After destruction, encrypted ciphertext is mathematically unrecoverable from this KMS instance. The ciphertext on disk (or in the OSD's in-memory store) is intact but useless without the key.

## Alternatives considered

**Stub the crypto (return `Base64(plaintext)`).** Teaches the API but not the property. A reader could correctly say "this isn't really crypto-shredding; you could just iterate the heap and find the plaintext". Rejected.

**Use a third-party library (BouncyCastle).** The JDK already has the primitives needed. BouncyCastle adds a dependency for no marginal benefit.

**Use AES-CBC + HMAC instead of GCM.** Older, more complex (two operations), more failure modes. AES-GCM is the standard authenticated-encryption-with-associated-data mode for new designs.

**Use libsodium / Tink.** Both reasonable. Tink's API is cleaner than the JDK Cipher API. Adds a dependency; the JDK API is well-known enough that readers don't lose much.

## Consequences

**Positive:**
- The crypto-shredding demonstration is honest: post-`destroyDuk`, the ciphertext genuinely cannot be decrypted by anyone, not just by this codebase.
- AES-GCM's authentication tag means tampering with ciphertext fails at decrypt time — a real property production systems rely on.
- The `Cipher` API shape is what readers will see in production code.
- No external library to vet.

**Negative:**
- Performance is real-AES-GCM speed (~1-2 GB/s on a modern CPU). For the test suite this doesn't matter; for any future benchmarks it would.
- `Cipher.getInstance("AES/GCM/NoPadding")` has known JDK-vendor variations (Sun JDK vs OpenJDK vs Azul) in edge cases. Stick to standard parameters.
- A `SecretKey` instance still lives in heap until `map.remove`; if the JVM is paged or core-dumped, the key bytes are recoverable. A production KMS uses HSM-backed keys that never enter user memory. This module doesn't simulate that.

## Implementation pointers

- `dfs-security/.../Kms.java` — the AES-GCM encrypt/decrypt path.
- `KmsTest` — pins the destroyed-key-throws property + tamper-detection.

## Related

- [`modules/dfs-security.md`](../modules/dfs-security.md)
- Wiki: [`concepts/key-shredding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/key-shredding.md)
- [ADR-0001](./0001-pure-java-no-jni.md) — the "pure Java" decision that constrained this to JDK Cipher
- [ADR-0002](./0002-in-memory-substrates.md) — adjacent: in-memory key store, but real crypto
