# ADR-0003: XOR-Based Parity Stub, Not Full Galois-Field Reed-Solomon

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

True Reed-Solomon erasure coding operates over a finite field — typically GF(2^8) — using polynomial arithmetic. Encoding produces `m` parity blocks; decoding can reconstruct any `m` lost blocks (data or parity) by solving a Vandermonde matrix inverse over the field. The math is well-understood and Intel ISA-L provides hand-optimised SIMD implementations.

A pure-Java GF(2^8) implementation is a few hundred lines of code (Jerasure is ~1500 LOC) but not a few dozen. Writing it correctly requires care with table-based multiplication, polynomial division, and matrix inversion. Getting it wrong silently produces wrong reconstructions — a particularly hostile failure mode for a teaching repo where readers will trust the code.

## Decision

In `dfs-erasure.ReedSolomon`, generate `m` parity blocks using iterated XOR-with-rotation. The output shape (`k+m` shards, decode-from-any-`k` survivors) matches Reed-Solomon. The internal math does NOT match — these parities are not true RS parities and the decoder requires all `k` data shards (cannot reconstruct missing data shards from parities). The class documents this in its Javadoc.

The wiki page on this concept ([`concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md)) describes true RS faithfully; readers consult the wiki for the math and this code for the shape.

## Alternatives considered

**Implement true GF(2^8) RS in pure Java.** Reasonable effort (~500 LOC + tables). Adds substantial code for one concept. The XOR stub shape teaches the same shape; the math is in the wiki.

**Use Jerasure or Backblaze's Reed-Solomon Java library.** Both are mature. Backblaze's library is ~1500 LOC of pure Java with no native bits — would work. The trade is: adds an external dependency the reader has to reason about, and the implementation details would mostly hide inside a library readers don't open.

**Use the BouncyCastle `RawAgreement` machinery.** Wrong abstraction; BouncyCastle is asymmetric crypto, not erasure coding.

**Implement only `Replication`; omit RS / LRC entirely.** Loses the ability to discuss the storage-overhead vs repair-bandwidth tradeoff in code. The wiki spends a lot of pages on this tradeoff; the code should at least mirror the shape.

## Consequences

**Positive:**
- One small class (88 LOC) demonstrates encode/decode shape without 500+ LOC of GF arithmetic.
- The teaching content focuses on the architectural decisions (when to use RS vs LRC vs replication; tier transitions), not on the math.
- No external library dependency.

**Negative:**
- The decoder cannot reconstruct missing data shards from parities — this is the headline "what RS actually does" — so `decode` rejects calls where data shards are missing.
- A reader who tries to extend the code (e.g. add ClayCodes) will find themselves needing to first replace this stub with real RS.
- Performance is not representative; XOR parity is faster than GF parity per byte and slower per operation than Intel ISA-L by orders of magnitude.

## Implementation pointers

- The stub: `dfs-erasure/.../ReedSolomon.java`, methods `encode` and `decode`.
- Tests that pin the documented limitation: `dfs-erasure/src/test/.../ReedSolomonTest.java#decodeFailsBelowQuorum`, `decodeReturnsOriginalBytes`.
- LRC builds on the stub: `dfs-erasure/.../LRC.java` provides cost calculations only — it never produces actual parity bytes.
- The Javadoc on `ReedSolomon` calls the stub out explicitly.

## Related

- [`modules/dfs-erasure.md`](../modules/dfs-erasure.md) §7 — the matching "Stubs and departures from production"
- Wiki: [`concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md), [`concepts/local-reconstruction-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/local-reconstruction-codes.md), [`concepts/clay-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/clay-codes.md)
