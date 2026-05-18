package com.hkg.dfs.lease;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants, renews, and revokes {@link ChunkLease}s. Backed by an in-memory
 * map keyed by {@link ChunkId}. Lease term defaults to 60 seconds; on
 * primary failure the monitor revokes the lease and a new primary is
 * selected from the surviving replicas.
 */
public final class LeaseService {
    private final ConcurrentHashMap<ChunkId, ChunkLease> leases = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration term;

    public LeaseService() {
        this(Clock.systemUTC(), Duration.ofSeconds(60));
    }

    public LeaseService(Clock clock, Duration term) {
        this.clock = clock;
        this.term = term;
    }

    public ChunkLease grant(ChunkId chunk, OsdId primary) {
        Instant now = clock.instant();
        ChunkLease lease = new ChunkLease(chunk, primary, now.plus(term));
        leases.put(chunk, lease);
        return lease;
    }

    public ChunkLease renew(ChunkId chunk) {
        return leases.compute(chunk, (k, cur) -> {
            if (cur == null) throw new IllegalStateException("no lease for " + chunk);
            return new ChunkLease(k, cur.primaryOsd(), clock.instant().plus(term));
        });
    }

    public void revoke(ChunkId chunk) {
        leases.remove(chunk);
    }

    public Optional<ChunkLease> get(ChunkId chunk) {
        return Optional.ofNullable(leases.get(chunk));
    }
}
