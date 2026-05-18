package com.hkg.dfs.placement;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.PgId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlacementTest {

    @Test
    void hashIsDeterministic() {
        Placement p = new Placement(64);
        PgId a = p.objectToPg(ObjectId.of("o"));
        PgId b = p.objectToPg(ObjectId.of("o"));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void pgWithinRange() {
        Placement p = new Placement(32);
        for (int i = 0; i < 1000; i++) {
            PgId id = p.objectToPg(ObjectId.of("o-" + i));
            assertThat(id.value()).isGreaterThanOrEqualTo(0).isLessThan(32);
        }
    }

    @Test
    void rejectsNonPositiveCount() {
        assertThatThrownBy(() -> new Placement(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distributesAcrossPgs() {
        Placement p = new Placement(16);
        int[] counts = new int[16];
        for (int i = 0; i < 1600; i++) counts[p.objectToPg(ObjectId.of("k-" + i)).value()]++;
        int nonZero = 0;
        for (int c : counts) if (c > 0) nonZero++;
        assertThat(nonZero).isGreaterThan(10);
    }
}
