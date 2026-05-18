package com.hkg.dfs.erasure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LRCTest {

    @Test
    void localGroupAssignment() {
        LRC lrc = new LRC(12, 3, 2); // 4 per group
        assertThat(lrc.localGroupOf(0)).isZero();
        assertThat(lrc.localGroupOf(3)).isZero();
        assertThat(lrc.localGroupOf(4)).isEqualTo(1);
        assertThat(lrc.localGroupOf(11)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new LRC(10, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LRC(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repairCostLocalSingle() {
        LRC lrc = new LRC(12, 3, 2);
        boolean[] surviving = allAlive(12);
        // group 0 has indices 0..3; failure at 0 needs peers 1,2,3 + local parity = 4 reads
        assertThat(lrc.repairCost(0, surviving)).isEqualTo(4);
    }

    @Test
    void repairCostFallsBackToGlobal() {
        LRC lrc = new LRC(12, 3, 2);
        boolean[] surviving = allAlive(12);
        // kill another block in the same group, then attempt to repair 0
        surviving[1] = false;
        // not group-repairable -> fallback to global => at least k reads
        int cost = lrc.repairCost(0, surviving);
        assertThat(cost).isGreaterThan(4);
    }

    @Test
    void repairCostBoundsCheck() {
        LRC lrc = new LRC(12, 3, 2);
        assertThatThrownBy(() -> lrc.repairCost(15, allAlive(12)))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void repairCostRejectsBadSurvivingArray() {
        LRC lrc = new LRC(12, 3, 2);
        assertThatThrownBy(() -> lrc.repairCost(0, new boolean[10]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupSizeDerivedFromKandL() {
        assertThat(new LRC(12, 3, 2).groupSize()).isEqualTo(4);
    }

    @Test
    void localGroupOutOfRange() {
        LRC lrc = new LRC(12, 3, 2);
        assertThatThrownBy(() -> lrc.localGroupOf(20))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void zeroGlobalCannotFallback() {
        LRC lrc = new LRC(12, 3, 0);
        boolean[] surviving = allAlive(12);
        surviving[1] = false;
        assertThatThrownBy(() -> lrc.repairCost(0, surviving))
                .isInstanceOf(IllegalStateException.class);
    }

    private static boolean[] allAlive(int n) {
        boolean[] s = new boolean[n];
        for (int i = 0; i < n; i++) s[i] = true;
        return s;
    }
}
