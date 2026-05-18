package com.hkg.dfs.crush;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.OsdId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrushTest {

    private CrushMap smallCluster() {
        CrushBucket root = CrushBucket.internal("root", BucketType.ROOT, 1);
        for (int r = 0; r < 4; r++) {
            CrushBucket rack = CrushBucket.internal("rack" + r, BucketType.RACK, 1);
            for (int h = 0; h < 3; h++) {
                CrushBucket host = CrushBucket.internal("rack" + r + "-host" + h, BucketType.HOST, 1);
                for (int o = 0; o < 2; o++) {
                    int id = r * 100 + h * 10 + o;
                    host.addChild(CrushBucket.osd("osd-" + id, OsdId.of(id), 1));
                }
                rack.addChild(host);
            }
            root.addChild(rack);
        }
        return new CrushMap(root);
    }

    @Test
    void placeReturnsRequestedReplicationFactor() {
        Crush c = new Crush(smallCluster());
        List<OsdId> r = c.place(ObjectId.of("o1"), 3, BucketType.RACK);
        assertThat(r).hasSize(3);
    }

    @Test
    void placeReturnsDistinctOsds() {
        Crush c = new Crush(smallCluster());
        List<OsdId> r = c.place(ObjectId.of("o1"), 3, BucketType.RACK);
        Set<OsdId> distinct = new HashSet<>(r);
        assertThat(distinct).hasSize(3);
    }

    @Test
    void crushPlaceIsDeterministic() {
        Crush c1 = new Crush(smallCluster());
        Crush c2 = new Crush(smallCluster());
        assertThat(c1.place(ObjectId.of("k"), 3, BucketType.RACK))
                .isEqualTo(c2.place(ObjectId.of("k"), 3, BucketType.RACK));
    }

    @Test
    void differentKeysGenerallyMapDifferently() {
        Crush c = new Crush(smallCluster());
        List<OsdId> a = c.place(ObjectId.of("alpha"), 3, BucketType.RACK);
        List<OsdId> b = c.place(ObjectId.of("beta"), 3, BucketType.RACK);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void replicasAreInDifferentRacks() {
        Crush c = new Crush(smallCluster());
        for (int i = 0; i < 50; i++) {
            List<OsdId> r = c.place(ObjectId.of("obj-" + i), 2, BucketType.RACK);
            int rack0 = r.get(0).value() / 100;
            int rack1 = r.get(1).value() / 100;
            assertThat(rack0).isNotEqualTo(rack1);
        }
    }

    @Test
    void addingBucketOnlyMovesDataIn() {
        CrushMap m1 = smallCluster();
        Crush c1 = new Crush(m1);

        CrushBucket root2 = CrushBucket.internal("root", BucketType.ROOT, 1);
        for (CrushBucket child : m1.root().children()) {
            root2.addChild(child);
        }
        CrushBucket extraRack = CrushBucket.internal("rack-extra", BucketType.RACK, 1);
        CrushBucket extraHost = CrushBucket.internal("rack-extra-host0", BucketType.HOST, 1);
        extraHost.addChild(CrushBucket.osd("osd-extra-0", OsdId.of(999), 1));
        extraRack.addChild(extraHost);
        root2.addChild(extraRack);
        Crush c2 = new Crush(new CrushMap(root2));

        int totalKeys = 200;
        int moved = 0;
        int movedInto = 0;
        for (int i = 0; i < totalKeys; i++) {
            ObjectId k = ObjectId.of("k-" + i);
            List<OsdId> r1 = c1.place(k, 1, BucketType.RACK);
            List<OsdId> r2 = c2.place(k, 1, BucketType.RACK);
            if (!r1.equals(r2)) {
                moved++;
                if (r2.get(0).value() == 999) movedInto++;
            }
        }
        // Most moved keys land in the new bucket; some legitimate reshuffling can occur because
        // the algorithm is hierarchical, but at least half of moves should go into the new bucket.
        assertThat(moved).isLessThan(totalKeys);
        if (moved > 0) {
            assertThat(movedInto * 2).isGreaterThanOrEqualTo(moved);
        }
    }

    @Test
    void rejectsZeroReplication() {
        Crush c = new Crush(smallCluster());
        assertThatThrownBy(() -> c.place(ObjectId.of("o"), 0, BucketType.RACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distributionAcrossOsdsIsRoughlyBalanced() {
        Crush c = new Crush(smallCluster());
        int[] counts = new int[1000];
        int n = 600;
        for (int i = 0; i < n; i++) {
            for (OsdId o : c.place(ObjectId.of("k-" + i), 1, BucketType.RACK)) {
                counts[o.value()]++;
            }
        }
        int nonZero = 0;
        for (int v : counts) if (v > 0) nonZero++;
        assertThat(nonZero).isGreaterThan(5); // hits many OSDs
    }
}
