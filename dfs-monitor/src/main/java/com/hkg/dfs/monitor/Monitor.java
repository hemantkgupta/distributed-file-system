package com.hkg.dfs.monitor;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.lease.ChunkLease;
import com.hkg.dfs.lease.LeaseService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory cluster monitor. Tracks OSD heartbeats, manages lease
 * lifecycle, publishes a versioned cluster map, and emits durability
 * events when a PG drops below its replication floor.
 */
public final class Monitor {
    private final LeaseService leases;
    private final ConcurrentHashMap<OsdId, OsdStatus> status = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OsdId, Integer> missedBeats = new ConcurrentHashMap<>();
    private final Map<PgId, List<OsdId>> pgMap = new HashMap<>();
    private final List<DurabilityEvent> events = new ArrayList<>();
    private final Set<PgId> reportedBelowFloor = new HashSet<>();
    private long mapVersion = 0;
    private final int missThreshold;
    private final int durabilityFloor;

    public Monitor(LeaseService leases, int missThreshold, int durabilityFloor) {
        this.leases = leases;
        this.missThreshold = missThreshold;
        this.durabilityFloor = durabilityFloor;
    }

    public void register(OsdId osd) {
        status.put(osd, OsdStatus.UP);
        missedBeats.put(osd, 0);
    }

    public ChunkLease grantLease(ChunkId chunk, OsdId primary) {
        return leases.grant(chunk, primary);
    }

    public void revokeLease(ChunkId chunk) {
        leases.revoke(chunk);
    }

    public synchronized long publishMap(Map<PgId, List<OsdId>> snapshot) {
        pgMap.clear();
        pgMap.putAll(snapshot);
        mapVersion++;
        return mapVersion;
    }

    public long mapVersion() { return mapVersion; }

    public OsdStatus statusOf(OsdId osd) { return status.getOrDefault(osd, OsdStatus.UP); }

    public synchronized void heartbeat(OsdId osd) {
        if (!status.containsKey(osd)) register(osd);
        missedBeats.put(osd, 0);
        status.put(osd, OsdStatus.UP);
    }

    public synchronized void tick() {
        // Increment every OSD's missed-beat counter; heartbeat() resets it.
        for (OsdId o : new ArrayList<>(status.keySet())) {
            int v = missedBeats.getOrDefault(o, 0) + 1;
            missedBeats.put(o, v);
            if (v >= missThreshold) {
                status.put(o, OsdStatus.DOWN);
            }
        }
        emitDurabilityEvents();
    }

    private void emitDurabilityEvents() {
        for (Map.Entry<PgId, List<OsdId>> e : pgMap.entrySet()) {
            int alive = 0;
            for (OsdId o : e.getValue()) {
                if (status.getOrDefault(o, OsdStatus.UP) == OsdStatus.UP) alive++;
            }
            if (alive < durabilityFloor) {
                if (reportedBelowFloor.add(e.getKey())) {
                    events.add(new DurabilityEvent(e.getKey(), alive, durabilityFloor));
                }
            } else {
                reportedBelowFloor.remove(e.getKey());
            }
        }
    }

    public synchronized List<DurabilityEvent> drainEvents() {
        List<DurabilityEvent> out = List.copyOf(events);
        events.clear();
        return out;
    }
}
