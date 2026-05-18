package com.hkg.dfs.crush;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrushMapTest {

    @Test
    void wrapsRoot() {
        CrushBucket root = CrushBucket.internal("root", BucketType.ROOT, 1);
        assertThat(new CrushMap(root).root()).isSameAs(root);
    }

    @Test
    void rejectsNonRootRoot() {
        CrushBucket rack = CrushBucket.internal("r", BucketType.RACK, 1);
        assertThatThrownBy(() -> new CrushMap(rack))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullRoot() {
        assertThatThrownBy(() -> new CrushMap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
