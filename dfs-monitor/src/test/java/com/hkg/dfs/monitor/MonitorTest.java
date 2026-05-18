package com.hkg.dfs.monitor;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.lease.LeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorTest {

    private Monitor monitor;
    private LeaseService leases;

    @BeforeEach
    void setUp() {
        leases = new LeaseService();
        monitor = new Monitor(leases, 3, 2);
    }

    @Test
    void registerSetsUp() {
        monitor.register(OsdId.of(0));
        assertThat(monitor.statusOf(OsdId.of(0))).isEqualTo(OsdStatus.UP);
    }

    @Test
    void heartbeatKeepsUp() {
        monitor.register(OsdId.of(0));
        monitor.tick();
        monitor.heartbeat(OsdId.of(0));
        monitor.tick();
        monitor.tick();
        assertThat(monitor.statusOf(OsdId.of(0))).isEqualTo(OsdStatus.UP);
    }

    @Test
    void missedHeartbeatsMarkDown() {
        monitor.register(OsdId.of(0));
        monitor.tick();
        monitor.tick();
        monitor.tick();
        assertThat(monitor.statusOf(OsdId.of(0))).isEqualTo(OsdStatus.DOWN);
    }

    @Test
    void grantLeaseStoresIt() {
        monitor.grantLease(ChunkId.of(1), OsdId.of(0));
        assertThat(leases.get(ChunkId.of(1))).isPresent();
    }

    @Test
    void revokeLeaseClears() {
        monitor.grantLease(ChunkId.of(1), OsdId.of(0));
        monitor.revokeLease(ChunkId.of(1));
        assertThat(leases.get(ChunkId.of(1))).isEmpty();
    }

    @Test
    void publishMapIncrementsVersion() {
        long v1 = monitor.publishMap(Map.of(PgId.of(0), List.of(OsdId.of(0))));
        long v2 = monitor.publishMap(Map.of(PgId.of(0), List.of(OsdId.of(0))));
        assertThat(v2).isGreaterThan(v1);
    }

    @Test
    void durabilityEventEmittedWhenBelowFloor() {
        monitor.register(OsdId.of(0));
        monitor.register(OsdId.of(1));
        monitor.publishMap(Map.of(PgId.of(0), List.of(OsdId.of(0), OsdId.of(1))));
        // Knock both osds down by ticking past the miss threshold
        for (int i = 0; i < 5; i++) monitor.tick();
        var events = monitor.drainEvents();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).floor()).isEqualTo(2);
    }

    @Test
    void noEventsWhenAboveFloor() {
        monitor.register(OsdId.of(0));
        monitor.register(OsdId.of(1));
        monitor.publishMap(Map.of(PgId.of(0), List.of(OsdId.of(0), OsdId.of(1))));
        monitor.heartbeat(OsdId.of(0));
        monitor.heartbeat(OsdId.of(1));
        monitor.tick();
        assertThat(monitor.drainEvents()).isEmpty();
    }

    @Test
    void drainEventsClears() {
        monitor.register(OsdId.of(0));
        monitor.publishMap(Map.of(PgId.of(0), List.of(OsdId.of(0))));
        for (int i = 0; i < 5; i++) monitor.tick();
        monitor.drainEvents();
        assertThat(monitor.drainEvents()).isEmpty();
    }

    @Test
    void unknownOsdReportsUp() {
        assertThat(monitor.statusOf(OsdId.of(99))).isEqualTo(OsdStatus.UP);
    }

    @Test
    void heartbeatRegistersIfMissing() {
        monitor.heartbeat(OsdId.of(7));
        assertThat(monitor.statusOf(OsdId.of(7))).isEqualTo(OsdStatus.UP);
    }

    @Test
    void mapVersionStartsAtZero() {
        assertThat(monitor.mapVersion()).isZero();
    }
}
