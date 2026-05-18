package com.hkg.dfs.placement;

import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockLayerTest {

    @Test
    void initialPutHasGenerationZero() {
        BlockLayer bl = new BlockLayer();
        PgLocation loc = bl.putInitial(PgId.of(1), List.of(OsdId.of(0), OsdId.of(1)));
        assertThat(loc.generation().value()).isZero();
    }

    @Test
    void duplicateInitialRejected() {
        BlockLayer bl = new BlockLayer();
        bl.putInitial(PgId.of(1), List.of(OsdId.of(0)));
        assertThatThrownBy(() -> bl.putInitial(PgId.of(1), List.of(OsdId.of(0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lookupMissingReturnsEmpty() {
        BlockLayer bl = new BlockLayer();
        assertThat(bl.lookup(PgId.of(99))).isEmpty();
    }

    @Test
    void lookupFindsInitialised() {
        BlockLayer bl = new BlockLayer();
        bl.putInitial(PgId.of(1), List.of(OsdId.of(0)));
        assertThat(bl.lookup(PgId.of(1))).isPresent();
    }

    @Test
    void updateIncrementsGeneration() {
        BlockLayer bl = new BlockLayer();
        bl.putInitial(PgId.of(1), List.of(OsdId.of(0)));
        PgLocation g1 = bl.updateLocation(PgId.of(1), List.of(OsdId.of(5)));
        assertThat(g1.generation().value()).isEqualTo(1);
    }

    @Test
    void updateChangesOsdList() {
        BlockLayer bl = new BlockLayer();
        bl.putInitial(PgId.of(1), List.of(OsdId.of(0)));
        PgLocation g1 = bl.updateLocation(PgId.of(1), List.of(OsdId.of(5)));
        assertThat(g1.osds()).containsExactly(OsdId.of(5));
    }

    @Test
    void updateUnknownPgFails() {
        BlockLayer bl = new BlockLayer();
        assertThatThrownBy(() -> bl.updateLocation(PgId.of(42), List.of(OsdId.of(0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void primaryIsFirstOsd() {
        BlockLayer bl = new BlockLayer();
        PgLocation loc = bl.putInitial(PgId.of(1), List.of(OsdId.of(7), OsdId.of(8)));
        assertThat(loc.primary()).isEqualTo(OsdId.of(7));
    }

    @Test
    void sizeReflectsPuts() {
        BlockLayer bl = new BlockLayer();
        bl.putInitial(PgId.of(1), List.of(OsdId.of(0)));
        bl.putInitial(PgId.of(2), List.of(OsdId.of(1)));
        assertThat(bl.size()).isEqualTo(2);
    }

    @Test
    void emptyOsdListRejected() {
        BlockLayer bl = new BlockLayer();
        assertThatThrownBy(() -> bl.putInitial(PgId.of(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
