package com.hkg.dfs.mds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubtreePartitionerTest {

    @Test
    void assignSetsOwner() {
        SubtreePartitioner p = new SubtreePartitioner();
        p.assign("/a", 1);
        assertThat(p.ownerOf("/a/x")).contains(1);
    }

    @Test
    void longestPrefixWins() {
        SubtreePartitioner p = new SubtreePartitioner();
        p.assign("/a", 1);
        p.assign("/a/b", 2);
        assertThat(p.ownerOf("/a/b/x")).contains(2);
    }

    @Test
    void noOwnerReturnsEmpty() {
        SubtreePartitioner p = new SubtreePartitioner();
        assertThat(p.ownerOf("/x")).isEmpty();
    }

    @Test
    void migrateChangesOwner() {
        SubtreePartitioner p = new SubtreePartitioner();
        p.assign("/a", 1);
        p.migrate("/a", 1, 2);
        assertThat(p.ownerOf("/a/x")).contains(2);
    }

    @Test
    void migrateFromWrongOwnerFails() {
        SubtreePartitioner p = new SubtreePartitioner();
        p.assign("/a", 1);
        assertThatThrownBy(() -> p.migrate("/a", 99, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migrateUnknownSubtreeFails() {
        SubtreePartitioner p = new SubtreePartitioner();
        assertThatThrownBy(() -> p.migrate("/zzz", 0, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignRejectsBadInput() {
        SubtreePartitioner p = new SubtreePartitioner();
        assertThatThrownBy(() -> p.assign("", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.assign("/a", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subtreeCountReflectsAssignments() {
        SubtreePartitioner p = new SubtreePartitioner();
        p.assign("/a", 0);
        p.assign("/b", 1);
        assertThat(p.subtreeCount()).isEqualTo(2);
    }
}
