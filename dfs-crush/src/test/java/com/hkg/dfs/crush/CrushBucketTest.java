package com.hkg.dfs.crush;

import com.hkg.dfs.common.OsdId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrushBucketTest {

    @Test
    void osdBucketHasOsdId() {
        CrushBucket b = CrushBucket.osd("o", OsdId.of(0), 1);
        assertThat(b.isLeaf()).isTrue();
        assertThat(b.osdId()).isEqualTo(OsdId.of(0));
    }

    @Test
    void internalRejectsOsdType() {
        assertThatThrownBy(() -> CrushBucket.internal("x", BucketType.OSD, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void osdLeafCannotHaveChildren() {
        CrushBucket leaf = CrushBucket.osd("o", OsdId.of(0), 1);
        assertThatThrownBy(() -> leaf.addChild(leaf))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void childrenAreUnmodifiable() {
        CrushBucket root = CrushBucket.internal("r", BucketType.ROOT, 1);
        root.addChild(CrushBucket.osd("o", OsdId.of(0), 1));
        assertThatThrownBy(() -> root.children().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
