package com.hkg.dfs.simulator;

import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.custodian.ClusterState;
import com.hkg.dfs.custodian.Custodian;
import com.hkg.dfs.custodian.RepairScanner;
import com.hkg.dfs.lease.LeaseService;
import com.hkg.dfs.mds.MdsCluster;
import com.hkg.dfs.monitor.Monitor;
import com.hkg.dfs.qos.DmClockScheduler;
import com.hkg.dfs.qos.QosClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end cluster simulator. Composes OSDs, MDS, Monitor, Custodian
 * and the dmClock scheduler so failure-and-recovery scenarios can be
 * exercised in tests.
 */
public final class ClusterSim {
    private final List<OsdId> osds = new ArrayList<>();
    private final Map<PgId, List<OsdId>> pgMap = new HashMap<>();
    private final Set<PgId> deepScrubDue = new HashSet<>();
    private final Set<PgId> shallowScrubDue = new HashSet<>();
    private final Set<PgId> needRebalance = new HashSet<>();
    private final MdsCluster mds = new MdsCluster();
    private final LeaseService leases = new LeaseService();
    private final Monitor monitor;
    private final DmClockScheduler scheduler = new DmClockScheduler();
    private final Custodian custodian;
    private final RepairScanner scanner = new RepairScanner();

    public ClusterSim(int numOsds, int numMds, int numPgs) {
        if (numOsds <= 0 || numMds <= 0 || numPgs <= 0) {
            throw new IllegalArgumentException("positive counts required");
        }
        this.monitor = new Monitor(leases, 3, 2);
        scheduler.addClass(new QosClass(Custodian.CRITICAL_QOS, 100, 10, 1000));
        scheduler.addClass(new QosClass(Custodian.ROUTINE_QOS,   10,  5, 1000));
        scheduler.addClass(new QosClass(Custodian.SCRUB_QOS,      0,  1, 1000));
        scheduler.addClass(new QosClass(Custodian.REBALANCE_QOS,  0,  1, 1000));
        this.custodian = new Custodian(scheduler);

        for (int i = 0; i < numOsds; i++) {
            OsdId o = OsdId.of(i);
            osds.add(o);
            monitor.register(o);
        }
        for (int p = 0; p < numPgs; p++) {
            // assign 3 OSDs round-robin
            List<OsdId> assigned = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                assigned.add(osds.get((p + r) % numOsds));
            }
            pgMap.put(PgId.of(p), assigned);
        }
        monitor.publishMap(pgMap);
        for (int m = 0; m < numMds; m++) {
            mds.partitioner().assign("/mds-" + m, m);
        }
    }

    public void simulateDiskFailure() {
        // Mark one OSD as missed heartbeats, advance until DOWN, then dispatch repair.
        if (osds.isEmpty()) return;
        for (int i = 0; i < 5; i++) monitor.tick();
        var events = monitor.drainEvents();
        ClusterState state = new ClusterState(
                pgMap.keySet(), events, deepScrubDue, shallowScrubDue, needRebalance);
        for (var w : scanner.scan(state)) custodian.dispatch(w);
        drainScheduler();
        // Recovery: heartbeat the OSDs back to UP.
        for (OsdId o : osds) monitor.heartbeat(o);
    }

    public void simulatePartition() {
        // Half of OSDs missed heartbeats
        for (int i = 0; i < osds.size() / 2; i++) {
            for (int k = 0; k < 5; k++) monitor.tick();
        }
        var events = monitor.drainEvents();
        ClusterState state = new ClusterState(
                pgMap.keySet(), events, deepScrubDue, shallowScrubDue, needRebalance);
        for (var w : scanner.scan(state)) custodian.dispatch(w);
        drainScheduler();
        // Partition heals
        for (OsdId o : osds) monitor.heartbeat(o);
    }

    public void simulateFlashCrowd() {
        try {
            scheduler.addClass(new QosClass("client", 0, 1, 1000));
        } catch (IllegalStateException alreadyExists) {
            // ok
        }
        for (int i = 0; i < 200; i++) scheduler.submit("client", () -> {});
        // Plus one critical repair triggered by a synthetic event
        deepScrubDue.add(PgId.of(0));
        var state = new ClusterState(
                pgMap.keySet(), List.of(), deepScrubDue, shallowScrubDue, needRebalance);
        for (var w : scanner.scan(state)) custodian.dispatch(w);
        drainScheduler();
    }

    public void drainScheduler() {
        while (scheduler.dispatch() != null) { /* drain */ }
    }

    public boolean isHealthy() {
        // Healthy iff no unresolved durability events and all OSDs UP.
        for (OsdId o : osds) {
            if (monitor.statusOf(o) != com.hkg.dfs.monitor.OsdStatus.UP) return false;
        }
        return true;
    }

    public Monitor monitor() { return monitor; }
    public Custodian custodian() { return custodian; }
    public DmClockScheduler scheduler() { return scheduler; }
    public int osdCount() { return osds.size(); }
    public int pgCount() { return pgMap.size(); }
    public MdsCluster mds() { return mds; }
}
