package com.hkg.dfs.custodian;

import com.hkg.dfs.common.PgId;
import com.hkg.dfs.monitor.DurabilityEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepairScannerTest {

    private static ClusterState empty() {
        return new ClusterState(Set.of(), List.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void emptyStateProducesNoWork() {
        assertThat(new RepairScanner().scan(empty())).isEmpty();
    }

    @Test
    void durabilityEventBecomesCriticalRepair() {
        ClusterState s = new ClusterState(
                Set.of(PgId.of(0)),
                List.of(new DurabilityEvent(PgId.of(0), 1, 2)),
                Set.of(), Set.of(), Set.of()
        );
        List<WorkItem> w = new RepairScanner().scan(s);
        assertThat(w.get(0).priority()).isEqualTo(PriorityClass.CRITICAL_REPAIR);
    }

    @Test
    void criticalRepairSortsAheadOfScrub() {
        ClusterState s = new ClusterState(
                Set.of(PgId.of(0), PgId.of(1)),
                List.of(new DurabilityEvent(PgId.of(1), 1, 2)),
                Set.of(PgId.of(0)), Set.of(), Set.of()
        );
        List<WorkItem> w = new RepairScanner().scan(s);
        assertThat(w.get(0).priority()).isEqualTo(PriorityClass.CRITICAL_REPAIR);
        assertThat(w.get(1).priority()).isEqualTo(PriorityClass.DEEP_SCRUB);
    }

    @Test
    void deepScrubBeforeShallowScrub() {
        ClusterState s = new ClusterState(
                Set.of(PgId.of(0), PgId.of(1)),
                List.of(),
                Set.of(PgId.of(0)), Set.of(PgId.of(1)), Set.of()
        );
        List<WorkItem> w = new RepairScanner().scan(s);
        assertThat(w.get(0).priority()).isEqualTo(PriorityClass.DEEP_SCRUB);
        assertThat(w.get(1).priority()).isEqualTo(PriorityClass.SHALLOW_SCRUB);
    }

    @Test
    void rebalanceItemEmitted() {
        ClusterState s = new ClusterState(
                Set.of(PgId.of(0)), List.of(), Set.of(), Set.of(), Set.of(PgId.of(0))
        );
        List<WorkItem> w = new RepairScanner().scan(s);
        assertThat(w).hasSize(1);
        assertThat(w.get(0).priority()).isEqualTo(PriorityClass.REBALANCE);
    }

    @Test
    void priorityOrderingIsConsistent() {
        ClusterState s = new ClusterState(
                Set.of(PgId.of(0), PgId.of(1), PgId.of(2), PgId.of(3)),
                List.of(new DurabilityEvent(PgId.of(0), 0, 2)),
                Set.of(PgId.of(1)), Set.of(PgId.of(2)), Set.of(PgId.of(3))
        );
        List<WorkItem> w = new RepairScanner().scan(s);
        for (int i = 0; i + 1 < w.size(); i++) {
            assertThat(w.get(i).priority().ordinal())
                    .isLessThanOrEqualTo(w.get(i + 1).priority().ordinal());
        }
    }

    @Test
    void workItemRejectsNullPg() {
        assertThatThrownBy(() -> new WorkItem(null, PriorityClass.REBALANCE, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workItemRejectsNullPriority() {
        assertThatThrownBy(() -> new WorkItem(PgId.of(0), null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workItemAllowsNullDescription() {
        WorkItem w = new WorkItem(PgId.of(0), PriorityClass.REBALANCE, null);
        assertThat(w.description()).isEmpty();
    }
}
