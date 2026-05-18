package com.hkg.dfs.allocator;

import java.util.Optional;

/**
 * Two-level bitmap allocator. L0 is a packed bitmap of unit-sized blocks;
 * L1 is a summary where each bit represents whether any of the corresponding
 * 64 L0 bits are free. This is the production-grade approach BlueStore uses
 * for the BlueFS allocator. See {@code wiki/concepts/bitmap-allocator}.
 */
public final class BitmapAllocator {
    private final long totalUnits;
    private final int unitSizeBytes;
    // bit=0 means free, bit=1 means allocated
    private final long[] l0;
    // l1 bit=0 means at least one free unit in the 64-unit group; 1 means full
    private final long[] l1;
    private long freeUnits;

    public BitmapAllocator(long totalUnits, int unitSizeBytes) {
        if (totalUnits <= 0) throw new IllegalArgumentException("totalUnits");
        if (unitSizeBytes <= 0) throw new IllegalArgumentException("unitSizeBytes");
        this.totalUnits = totalUnits;
        this.unitSizeBytes = unitSizeBytes;
        int l0Words = (int) ((totalUnits + 63) / 64);
        this.l0 = new long[l0Words];
        int l1Words = (l0Words + 63) / 64;
        this.l1 = new long[l1Words];
        this.freeUnits = totalUnits;
    }

    public synchronized Optional<Range> allocate(int units) {
        if (units <= 0) throw new IllegalArgumentException("units");
        if (units > freeUnits) return Optional.empty();
        // first-fit scan, skipping fully-allocated L0 words via L1
        long start = -1;
        int run = 0;
        for (int w = 0; w < l0.length; w++) {
            if (isL0WordFull(w)) {
                start = -1;
                run = 0;
                continue;
            }
            int bitsInWord = (int) Math.min(64L, totalUnits - (long) w * 64L);
            for (int b = 0; b < bitsInWord; b++) {
                long mask = 1L << b;
                if ((l0[w] & mask) == 0) {
                    if (run == 0) start = (long) w * 64L + b;
                    run++;
                    if (run == units) {
                        markRange(start, units, true);
                        freeUnits -= units;
                        return Optional.of(new Range(start, units));
                    }
                } else {
                    start = -1;
                    run = 0;
                }
            }
        }
        return Optional.empty();
    }

    public synchronized void free(Range range) {
        if (range == null) throw new IllegalArgumentException("range");
        if (range.endExclusive() > totalUnits) {
            throw new IllegalArgumentException("range out of bounds");
        }
        markRange(range.start(), (int) range.length(), false);
        freeUnits += range.length();
    }

    public synchronized long freeUnits() { return freeUnits; }

    public long totalUnits() { return totalUnits; }

    public int unitSizeBytes() { return unitSizeBytes; }

    private boolean isL0WordFull(int w) {
        int l1Word = w / 64;
        int l1Bit = w % 64;
        return (l1[l1Word] & (1L << l1Bit)) != 0;
    }

    private void setL1Full(int w, boolean full) {
        int l1Word = w / 64;
        int l1Bit = w % 64;
        if (full) l1[l1Word] |= (1L << l1Bit);
        else l1[l1Word] &= ~(1L << l1Bit);
    }

    private void markRange(long start, int units, boolean allocate) {
        long end = start + units;
        for (long u = start; u < end; u++) {
            int w = (int) (u / 64);
            int b = (int) (u % 64);
            long mask = 1L << b;
            if (allocate) l0[w] |= mask;
            else l0[w] &= ~mask;
        }
        // refresh L1 for affected words
        int firstW = (int) (start / 64);
        int lastW = (int) ((end - 1) / 64);
        for (int w = firstW; w <= lastW; w++) {
            setL1Full(w, l0WordIsFull(w));
        }
    }

    private boolean l0WordIsFull(int w) {
        long bitsInWord = Math.min(64L, totalUnits - (long) w * 64L);
        long mask = bitsInWord == 64 ? -1L : ((1L << bitsInWord) - 1);
        return (l0[w] & mask) == mask;
    }
}
