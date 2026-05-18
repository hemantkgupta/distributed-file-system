package com.hkg.dfs.allocator;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitmapAllocatorTest {

    @Test
    void initialFreeMatchesTotal() {
        BitmapAllocator a = new BitmapAllocator(1024, 4096);
        assertThat(a.freeUnits()).isEqualTo(1024);
    }

    @Test
    void allocateSucceedsWhenSpaceAvailable() {
        BitmapAllocator a = new BitmapAllocator(1024, 4096);
        Optional<Range> r = a.allocate(8);
        assertThat(r).isPresent();
        assertThat(r.orElseThrow().length()).isEqualTo(8);
    }

    @Test
    void allocateReducesFreeCount() {
        BitmapAllocator a = new BitmapAllocator(1024, 4096);
        a.allocate(16);
        assertThat(a.freeUnits()).isEqualTo(1008);
    }

    @Test
    void freeRestoresUnits() {
        BitmapAllocator a = new BitmapAllocator(1024, 4096);
        Range r = a.allocate(16).orElseThrow();
        a.free(r);
        assertThat(a.freeUnits()).isEqualTo(1024);
    }

    @Test
    void allocationExhaustion() {
        BitmapAllocator a = new BitmapAllocator(64, 512);
        assertThat(a.allocate(64)).isPresent();
        assertThat(a.allocate(1)).isEmpty();
    }

    @Test
    void rejectsOverAllocationRequest() {
        BitmapAllocator a = new BitmapAllocator(16, 512);
        assertThat(a.allocate(100)).isEmpty();
    }

    @Test
    void rejectsNonPositiveUnitsInAllocate() {
        BitmapAllocator a = new BitmapAllocator(16, 512);
        assertThatThrownBy(() -> a.allocate(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contiguousAllocationsAreAdjacent() {
        BitmapAllocator a = new BitmapAllocator(64, 512);
        Range r1 = a.allocate(8).orElseThrow();
        Range r2 = a.allocate(8).orElseThrow();
        assertThat(r2.start()).isEqualTo(r1.endExclusive());
    }

    @Test
    void freeAndReallocateReturnsSameRegion() {
        BitmapAllocator a = new BitmapAllocator(64, 512);
        a.allocate(8); // hold
        Range r2 = a.allocate(8).orElseThrow();
        a.free(r2);
        Range r3 = a.allocate(8).orElseThrow();
        // Best-fit / first-fit reuses freed region.
        assertThat(r3.start()).isEqualTo(r2.start());
    }

    @Test
    void fragmentationStillFindsRange() {
        BitmapAllocator a = new BitmapAllocator(128, 512);
        Range r1 = a.allocate(8).orElseThrow();
        Range r2 = a.allocate(8).orElseThrow();
        a.free(r1); // create a gap
        // The next 8-unit alloc should still find a contiguous run
        assertThat(a.allocate(8)).isPresent();
    }

    @Test
    void allocateCrossesL0WordBoundary() {
        BitmapAllocator a = new BitmapAllocator(256, 512);
        Range r = a.allocate(70).orElseThrow();
        assertThat(r.length()).isEqualTo(70);
    }

    @Test
    void freeOutOfBoundsRejected() {
        BitmapAllocator a = new BitmapAllocator(16, 512);
        assertThatThrownBy(() -> a.free(new Range(20, 8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitSizeRetained() {
        BitmapAllocator a = new BitmapAllocator(16, 8192);
        assertThat(a.unitSizeBytes()).isEqualTo(8192);
    }
}
