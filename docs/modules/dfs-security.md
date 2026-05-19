# dfs-security

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The in-process Key Management Service implementing crypto-shredding. Each `(tenant, object)` pair gets its own AES-128 Data Unique Key. `encrypt(dukId, plaintext)` returns AES-GCM ciphertext with a fresh 96-bit IV; `decrypt(dukId, ciphertext)` returns the plaintext. `destroyDuk(dukId)` removes the key from the in-memory map; subsequent decrypt attempts throw `KeyDestroyedException`.

This is the only place in the repo that uses real cryptography. Everywhere else stubs.

## 2. Wiki anchor

[`wiki/concepts/key-shredding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/key-shredding.md). The wiki page argues that crypto-shredding is the only viable GDPR erasure mechanism on immutable EC-encoded storage — destroying the key in milliseconds is operationally simpler than rewriting every shard.

## 3. Public API surface

```java
package com.hkg.dfs.security;

public final class Kms {
    public Kms();    // generates a fresh SecureRandom

    public DukId generateDuk(TenantId tenant, ObjectId obj);
    public byte[] encrypt(DukId id, byte[] plaintext);      // throws KeyDestroyedException if id was destroyed
    public byte[] decrypt(DukId id, byte[] ciphertext);     // throws if key is destroyed or ciphertext is corrupted
    public void destroyDuk(DukId id);
    public boolean hasKey(DukId id);
}

public record DukId(TenantId tenant, ObjectId object) {}

public final class KeyDestroyedException extends RuntimeException {
    public KeyDestroyedException(DukId id);   // message: "key destroyed for " + id
}
```

Source: `dfs-security/src/main/java/com/hkg/dfs/security/`.

## 4. Internal structure

- **`keys`** — `ConcurrentHashMap<DukId, SecretKey>`. The full lifecycle (create / encrypt / decrypt / destroy) flows through this single map.
- **`random`** — `SecureRandom` for IVs.
- **Encrypt flow**:
  1. Generate 12-byte IV.
  2. Initialise `Cipher.getInstance("AES/GCM/NoPadding")` in encrypt mode with a 128-bit GCM tag.
  3. Encrypt the plaintext.
  4. Return `iv || ciphertext` (IV prepended).
- **Decrypt flow**:
  1. Split first 12 bytes as IV.
  2. Decrypt the rest.
  3. Return plaintext. If the key is gone or the tag is wrong, throws.

The AES-GCM authentication tag makes the cryptography secure: a tampered ciphertext fails the tag check and decrypt throws.

## 5. Key tests

10 tests in `KmsTest`.

| Test | Demonstrates |
|---|---|
| `generateDukRegistersKey` | `generateDuk(tenant, obj)` returns a DukId; `hasKey(id) == true`. |
| `encryptDecryptRoundTrip` | `decrypt(id, encrypt(id, p)) == p` byte-for-byte. |
| `destroyedKeyDecryptThrows` | After `destroyDuk(id)`, a previously-encrypted ciphertext is no longer decryptable. |
| `hasKeyReturnsFalseAfterDestroy` | `hasKey(id) == false` after `destroyDuk(id)`. |
| `tenantIsolation` | Tenant A's DUK cannot decrypt a ciphertext encrypted under tenant B's DUK. |
| `encryptYieldsDifferentCiphertextsEachCall` | Random 96-bit IV per call → two encrypts of the same plaintext produce different ciphertexts. |
| `destroyedKeyEncryptThrows` | Defense-in-depth: encrypt is rejected if the key is gone. |
| `rejectsNullPlaintext` | Defensive validation on the encrypt path. |
| `dukIdRejectsNulls` | `DukId` record rejects null tenant or null object. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator` could exercise it in a GDPR-erasure scenario; currently doesn't.

**Downstream dependencies:** `dfs-common` (uses `TenantId` + `ObjectId`).

**The dependency rule:** the KMS knows about tenant boundaries and object identity. It does NOT know about OSDs, placement, leases, or any storage detail. It's a cryptographic component.

## 7. Stubs and departures from production

- **In-process keys.** A real KMS (AWS KMS, GCP KMS, HashiCorp Vault) stores keys in HSMs, supports key rotation, and serves over network. This module's keys live in the JVM heap.
- **No envelope encryption.** Production schemes typically use a master key in the KMS to wrap per-object DUKs; the DUKs themselves live near the data. This module conflates the two — every DUK is directly in the KMS.
- **No audit logging.** A real KMS logs every encrypt / decrypt / destroy. This module doesn't.
- **No rotation.** A real KMS supports key rotation (new key version generated; old keys remain for decryption of pre-rotation ciphertext until they're explicitly destroyed). This module has one version per DUK.
- **Single-process.** A real KMS is replicated, HA, and clusterable. This module is one JVM.

What IS production-grade: the actual cryptographic primitives. AES-GCM with 128-bit tags and 96-bit random IVs is the standard authenticated encryption mode. The same Cipher class shape is what a real KMS client uses.
