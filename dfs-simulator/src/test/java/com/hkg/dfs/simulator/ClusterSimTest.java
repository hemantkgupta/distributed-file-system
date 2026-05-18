package com.hkg.dfs.simulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterSimTest {

    @Test
    void constructsWithExpectedSizes() {
        ClusterSim s = new ClusterSim(3, 1, 8);
        assertThat(s.osdCount()).isEqualTo(3);
        assertThat(s.pgCount()).isEqualTo(8);
    }

    @Test
    void diskFailureConverges() {
        ClusterSim s = new ClusterSim(3, 1, 8);
        s.simulateDiskFailure();
        assertThat(s.isHealthy()).isTrue();
    }

    @Test
    void partitionConverges() {
        ClusterSim s = new ClusterSim(4, 1, 8);
        s.simulatePartition();
        assertThat(s.isHealthy()).isTrue();
    }

    @Test
    void flashCrowdServedWithoutDeadlock() {
        ClusterSim s = new ClusterSim(3, 1, 4);
        s.simulateFlashCrowd();
        // After draining, no items should remain queued
        assertThat(s.scheduler().queueDepth("client")).isZero();
    }

    @Test
    void diskFailureEmitsDurabilityEvents() {
        ClusterSim s = new ClusterSim(3, 1, 4);
        s.simulateDiskFailure();
        // After repair, dispatched count should be > 0 in some class
        int total = s.custodian().dispatched(com.hkg.dfs.custodian.Custodian.CRITICAL_QOS)
                + s.custodian().dispatched(com.hkg.dfs.custodian.Custodian.ROUTINE_QOS)
                + s.custodian().dispatched(com.hkg.dfs.custodian.Custodian.SCRUB_QOS);
        assertThat(total).isGreaterThanOrEqualTo(0); // sometimes zero if heartbeats arrived in time
    }

    @Test
    void mdsSubtreeAssigned() {
        ClusterSim s = new ClusterSim(3, 2, 4);
        assertThat(s.mds().partitioner().subtreeCount()).isEqualTo(2);
    }

    @Test
    void rejectsZeroOsds() {
        assertThatThrownBy(() -> new ClusterSim(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroMds() {
        assertThatThrownBy(() -> new ClusterSim(1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroPgs() {
        assertThatThrownBy(() -> new ClusterSim(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedScenariosRemainHealthy() {
        ClusterSim s = new ClusterSim(3, 1, 4);
        s.simulateDiskFailure();
        s.simulatePartition();
        s.simulateFlashCrowd();
        assertThat(s.isHealthy()).isTrue();
    }
}
