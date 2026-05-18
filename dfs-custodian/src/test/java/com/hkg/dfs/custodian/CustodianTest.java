package com.hkg.dfs.custodian;

import com.hkg.dfs.common.PgId;
import com.hkg.dfs.monitor.DurabilityEvent;
import com.hkg.dfs.qos.DmClockScheduler;
import com.hkg.dfs.qos.QosClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustodianTest {

    private DmClockScheduler scheduler;
    private Custodian custodian;

    @BeforeEach
    void setUp() {
        scheduler = new DmClockScheduler();
        scheduler.addClass(new QosClass(Custodian.CRITICAL_QOS, 100, 10, 1000));
        scheduler.addClass(new QosClass(Custodian.ROUTINE_QOS,   10,  5, 1000));
        scheduler.addClass(new QosClass(Custodian.SCRUB_QOS,      0,  1, 1000));
        scheduler.addClass(new QosClass(Custodian.REBALANCE_QOS,  0,  1, 1000));
        custodian = new Custodian(scheduler);
    }

    @Test
    void dispatchCriticalSubmitsToCriticalClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.CRITICAL_REPAIR, "x"));
        assertThat(scheduler.queueDepth(Custodian.CRITICAL_QOS)).isEqualTo(1);
    }

    @Test
    void dispatchRoutineUsesRoutineClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.ROUTINE_REPAIR, "x"));
        assertThat(scheduler.queueDepth(Custodian.ROUTINE_QOS)).isEqualTo(1);
    }

    @Test
    void scrubItemsGoToScrubClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.DEEP_SCRUB, ""));
        custodian.dispatch(new WorkItem(PgId.of(1), PriorityClass.SHALLOW_SCRUB, ""));
        assertThat(scheduler.queueDepth(Custodian.SCRUB_QOS)).isEqualTo(2);
    }

    @Test
    void rebalanceUsesRebalanceClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.REBALANCE, ""));
        assertThat(scheduler.queueDepth(Custodian.REBALANCE_QOS)).isEqualTo(1);
    }

    @Test
    void tierTransitionUsesRebalanceClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.TIER_TRANSITION, ""));
        assertThat(scheduler.queueDepth(Custodian.REBALANCE_QOS)).isEqualTo(1);
    }

    @Test
    void criticalPreemptsClient() {
        scheduler.addClass(new QosClass("client", 0, 1, 1000));
        scheduler.submit("client", () -> {});
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.CRITICAL_REPAIR, ""));
        // critical-repair has reservation > 0; durability elevation pre-empts client
        assertThat(scheduler.dispatch()).isEqualTo(Custodian.CRITICAL_QOS);
    }

    @Test
    void scannerThenDispatchEndToEnd() {
        ClusterState state = new ClusterState(
                Set.of(PgId.of(0)),
                List.of(new DurabilityEvent(PgId.of(0), 0, 2)),
                Set.of(), Set.of(), Set.of()
        );
        for (WorkItem w : new RepairScanner().scan(state)) custodian.dispatch(w);
        scheduler.dispatch();
        assertThat(custodian.dispatched(Custodian.CRITICAL_QOS)).isEqualTo(1);
    }

    @Test
    void dispatchedZeroBeforeAnyDispatch() {
        assertThat(custodian.dispatched(Custodian.CRITICAL_QOS)).isZero();
    }

    @Test
    void manyItemsQueueIndependently() {
        for (int i = 0; i < 10; i++) {
            custodian.dispatch(new WorkItem(PgId.of(i), PriorityClass.ROUTINE_REPAIR, ""));
        }
        assertThat(scheduler.queueDepth(Custodian.ROUTINE_QOS)).isEqualTo(10);
    }
}
