# dfs-erasure

> Last reconciled with the repo on 2026-05-20.

## 1. Role

Three side-by-side redundancy schemes, so the tier-transition story (3× replication for hot, LRC for warm, ClayCodes for cold) can be expressed in code:

- **`Replication(n)`** — n copies. Storage cost n×, repair-read 1×.
- **`ReedSolomon(k, m)`** — k data + m parity blocks. Storage cost (k+m)/k. Repair-read k blocks.
- **`LRC(k, l, g)`** — k data, l local parity groups, g global parities. Single-block repair stays inside one local group; cost ≈ groupSize + 1.

The shapes are correct (replicate-then-decode, RS-style decode-from-any-k, LRC group / global fallback). The XOR-parity stub is honest about being a stub — see §7.

## 2. Wiki anchors

- [`wiki/concepts/erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/erasure-coding.md)
- [`wiki/concepts/local-reconstruction-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/local-reconstruction-codes.md)
- [`wiki/tradeoffs/rs-vs-lrc-erasure-coding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/tradeoffs/rs-vs-lrc-erasure-coding.md)

## 3. Public API surface

```java
package com.hkg.dfs.erasure;

public final class Replication {
    public Replication(int factor);
    public List<byte[]> encode(byte[] payload);       // returns factor copies
    public byte[] decode(List<byte[]> shards);        // returns first non-null
    public int factor();
}

public final class ReedSolomon {
    public ReedSolomon(int k, int m);
    public List<byte[]> encode(byte[] payload);       // returns k data + m parity
    public byte[] decode(List<byte[]> shards, int originalLength);
    public int k(); public int m(); public int totalShards();
}

public final class LRC {
    public LRC(int k, int l, int g);                  // k data, l local groups, g globals
    public int localGroupOf(int blockIndex);
    public int repairCost(int failedIndex, boolean[] surviving);
    public int k(); public int l(); public int g(); public int groupSize();
}
```

Source: `dfs-erasure/src/main/java/com/hkg/dfs/erasure/`.

## 4. Internal structure

- **`Replication`** — trivial. `encode` deep-copies the payload N times. `decode` returns the first non-null shard.
- **`ReedSolomon`** — encode splits the payload into k equal-size data blocks (zero-padded), then computes m parity blocks via iterated XOR-with-rotation. The "rotation" is `(i + j) % blockLen` for the j-th parity over the i-th data — designed to produce different parity blocks across the m parities, but it is NOT a true Galois-field operation. Decode requires all k data shards to be present; a real RS decoder solves the parity inverse to reconstruct missing data shards. See [ADR-0003](../decisions/0003-xor-parity-stub-not-galois.md).
- **`LRC`** — does no encoding; instead, computes the *cost* of repair under a given survivor pattern. The interesting method is `repairCost`: if every peer in the failed block's local group survives, return `groupSize` (read peers + local parity); otherwise fall back to global parity at cost `min(k, surviveCount) + 1`. This is what the wiki and ADRs argue is the win of LRC — the common-case cost.

The `k % l != 0` constructor check enforces that data blocks divide evenly into local groups.

## 5. Key tests

22 tests across `ReplicationTest`, `ReedSolomonTest`, `LRCTest`.

| Test | Demonstrates |
|---|---|
| `ReplicationTest.encodeProducesFactorCopies` | N=3 returns three copies. |
| `ReplicationTest.decodeWithOneSurvivor` | Tolerates a shard list with only one non-null entry. |
| `ReedSolomonTest.encodeReturnsKplusMShards` | The shard list has length k+m. |
| `ReedSolomonTest.decodeRoundTrip` | With all k data shards present, decode reconstructs the original (within zero-pad). |
| `ReedSolomonTest.decodeFailsWithTooFewSurvivors` | Fewer than k survivors → throws. |
| `LRCTest.localGroupAssignment` | k=12, l=3 → blocks partition cleanly into l local groups. |
| `LRCTest.repairCostLocalSingle` | Single block fails, group peers survive → cost stays inside the local group. |
| `LRCTest.repairCostFallsBackToGlobal` | Local group has a second failure → falls back to global parity. |
| `LRCTest.rejectsInvalidParameters` | `LRC` with `k` not divisible by `l` throws. |

## 6. Where it fits

**Upstream consumers:** `dfs-simulator` (uses these in tier-transition scenarios).

**Downstream dependencies:** none.

**The dependency rule:** the EC modules have no opinion on placement, on which OSDs hold which shards, or on the cluster state. They are pure math.

## 7. Stubs and departures from production

- **XOR parity is not true Reed-Solomon.** A real RS decoder solves a Vandermonde matrix inverse over GF(2^8) to reconstruct missing data shards from any k surviving shards (data or parity). This module's `decode` rejects calls where data shards are missing. See [ADR-0003](../decisions/0003-xor-parity-stub-not-galois.md).
- **No actual encoded data in LRC.** `LRC.repairCost` computes the *cost number*; it does not produce parity bytes. A production LRC implementation would extend the RS encoder with local-group parities.
- **No ClayCodes.** Mentioned in the wiki at [`concepts/clay-codes`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/clay-codes.md); this repo does not implement them.
- **No Intel ISA-L bindings.** Production EC uses Intel ISA-L for SIMD-accelerated GF arithmetic. Pure-Java is fine for the teaching demo; not fine for petabyte-scale traffic.
