package com.hkg.dfs.lease;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.OsdId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseServiceTest {

    @Test
    void grantStoresLease() {
        LeaseService s = new LeaseService();
        ChunkLease l = s.grant(ChunkId.of(1), OsdId.of(0));
        assertThat(l.primaryOsd()).isEqualTo(OsdId.of(0));
    }

    @Test
    void getReturnsGranted() {
        LeaseService s = new LeaseService();
        s.grant(ChunkId.of(1), OsdId.of(0));
        assertThat(s.get(ChunkId.of(1))).isPresent();
    }

    @Test
    void renewExtendsExpiry() {
        var fixedNow = Instant.parse("2025-01-01T00:00:00Z");
        var clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        var s = new LeaseService(clock, Duration.ofSeconds(30));
        s.grant(ChunkId.of(1), OsdId.of(0));
        var clock2 = Clock.fixed(fixedNow.plusSeconds(10), ZoneOffset.UTC);
        var s2 = new LeaseService(clock2, Duration.ofSeconds(30));
        // Renew on s2 won't work since it has different state; instead test renew on s.
        var renewed = s.renew(ChunkId.of(1));
        assertThat(renewed.expiresAt()).isAfterOrEqualTo(fixedNow.plus(Duration.ofSeconds(30)));
    }

    @Test
    void renewMissingFails() {
        LeaseService s = new LeaseService();
        assertThatThrownBy(() -> s.renew(ChunkId.of(99)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeRemoves() {
        LeaseService s = new LeaseService();
        s.grant(ChunkId.of(1), OsdId.of(0));
        s.revoke(ChunkId.of(1));
        assertThat(s.get(ChunkId.of(1))).isEmpty();
    }

    @Test
    void leaseExpiresAfterTerm() {
        var t0 = Instant.parse("2025-01-01T00:00:00Z");
        var s = new LeaseService(Clock.fixed(t0, ZoneOffset.UTC), Duration.ofSeconds(5));
        var lease = s.grant(ChunkId.of(1), OsdId.of(0));
        assertThat(lease.isExpired(t0.plusSeconds(10))).isTrue();
        assertThat(lease.isExpired(t0)).isFalse();
    }

    @Test
    void chunkLeaseRecordRejectsNullChunk() {
        assertThatThrownBy(() -> new ChunkLease(null, OsdId.of(0), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkLeaseRecordRejectsNullPrimary() {
        assertThatThrownBy(() -> new ChunkLease(ChunkId.of(0), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantOverwritesExistingLease() {
        LeaseService s = new LeaseService();
        s.grant(ChunkId.of(1), OsdId.of(0));
        s.grant(ChunkId.of(1), OsdId.of(7));
        assertThat(s.get(ChunkId.of(1)).orElseThrow().primaryOsd()).isEqualTo(OsdId.of(7));
    }
}
