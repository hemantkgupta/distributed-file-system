# dfs-allocator

> Last reconciled with the repo on 2026-05-20.

## 1. Role

Free-space tracking for one OSD's local block device. The bitmap allocator is BlueStore's choice over a B-tree allocator: predictable memory footprint regardless of fragmentation, O(1) amortised allocation. This module is a self-contained, no-dependency, in-process implementation of the L0 + L1 cascade.

## 2. Wiki anchor

[`wiki/concepts/bitmap-allocator`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/bitmap-allocator.md).

## 3. Public API surface

```java
package com.hkg.dfs.allocator;

public final class BitmapAllocator {
    public BitmapAllocator(long totalUnits, int unitSizeBytes);

    public synchronized Optional<Range> allocate(int units);  // first-fit
    public synchronized void free(Range range);
    public synchronized long freeUnits();

    public long totalUnits();
    public int unitSizeBytes();
}

public record Range(long start, long length) {
    public long endExclusive();
}
```

Source: `dfs-allocator/src/main/java/com/hkg/dfs/allocator/`.

## 4. Internal structure

Two bitmap levels:

- **L0** — one bit per allocation unit, packed into `long[]`. `0` = free, `1` = allocated.
- **L1** — one bit per 64-L0-bit word. Set when the word is fully allocated. Allows skipping fully-allocated regions in O(1) during a scan.

The allocation algorithm is first-fit: walk L0 word-by-word, skipping words flagged in L1, looking for a run of `units` consecutive zero bits. Found → mark the range allocated, refresh L1 for affected words, decrement `freeUnits`.

Memory footprint is `totalUnits / 8` bytes for L0 and `totalUnits / 512` bytes for L1 — totally independent of fragmentation. A 20 TB HDD with 64 KB allocation units carries ~40 MB of L0 plus ~625 KB of L1.

Thread safety: all public methods are `synchronized`. The internal mutations (mark + L1 refresh) are not safe to interleave.

## 5. Key tests

13 tests in `BitmapAllocatorTest`.

| Test | Demonstrates |
|---|---|
| `initialFreeMatchesTotal` | A fresh allocator reports `freeUnits() == totalUnits`. |
| `allocateSucceedsWhenSpaceAvailable` | A small allocation in an empty allocator returns a `Range`. |
| `allocateReducesFreeCount` | After allocating N units, `freeUnits()` drops by N. |
| `freeRestoresUnits` | After `free`, `freeUnits()` increments by the released range length. |
| `rejectsOverAllocationRequest` | Requesting more than total returns empty. |
| `contiguousAllocationsAreAdjacent` | Sequential allocations land on adjacent ranges. |
| `fragmentationStillFindsRange` | After allocating-then-freeing many small ranges, an allocation in a hole succeeds. |
| `allocateCrossesL0WordBoundary` | An allocation that spans more than one 64-bit L0 word still finds a contiguous run. |
| `freeAndReallocateReturnsSameRegion` | After free, the same region can be re-allocated. |

## 6. Where it fits

**Upstream consumers:** `dfs-storage` (uses it to find space for new extents).

**Downstream dependencies:** none beyond JDK.

**The dependency rule:** the allocator is workload-blind. It doesn't know what an object is, what a PG is, or what an OSD is. It allocates units on a block device.

## 7. Stubs and departures from production

- **First-fit, not best-fit.** Production allocators may use best-fit or buddy-system variants for specific fragmentation patterns. First-fit is the simplest and fast enough for the OSD workload.
- **Single allocator, single device.** Real OSDs may carve a device into regions with different allocation policies (e.g. metadata extents always near the start). This module is one flat region.
- **No persistence.** The bitmap exists only in RAM. A real OSD persists allocation state durably (typically in RocksDB column families). On restart, the allocator is reconstructed from the persisted state.
- **No discard / TRIM coordination.** Production allocators issue `BLKDISCARD` / TRIM to the underlying SSD when units are freed. This module just flips bits.
