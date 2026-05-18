package com.hkg.dfs.crush;

import com.hkg.dfs.common.OsdId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrawSelectorTest {

    @Test
    void selectsSameForSameSeed() {
        StrawSelector s = new StrawSelector();
        List<CrushBucket> cs = List.of(
                CrushBucket.osd("a", OsdId.of(0), 1),
                CrushBucket.osd("b", OsdId.of(1), 1),
                CrushBucket.osd("c", OsdId.of(2), 1)
        );
        assertThat(s.select(cs, 42, 0)).isSameAs(s.select(cs, 42, 0));
    }

    @Test
    void rejectsEmpty() {
        StrawSelector s = new StrawSelector();
        assertThatThrownBy(() -> s.select(List.of(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleCandidateIsAlwaysChosen() {
        StrawSelector s = new StrawSelector();
        CrushBucket only = CrushBucket.osd("only", OsdId.of(0), 1);
        assertThat(s.select(List.of(only), 0, 0)).isSameAs(only);
    }
}
