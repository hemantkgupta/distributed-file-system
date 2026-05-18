package com.hkg.dfs.node;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.crush.BucketType;
import com.hkg.dfs.crush.Crush;
import com.hkg.dfs.crush.CrushBucket;
import com.hkg.dfs.crush.CrushMap;
import com.hkg.dfs.lease.ExtentService;
import com.hkg.dfs.lease.LeaseService;
import com.hkg.dfs.placement.BlockLayer;
import com.hkg.dfs.placement.Placement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeApiTest {

    private NodeApi node;
    private ExtentService extents;

    @BeforeEach
    void setUp() {
        CrushBucket root = CrushBucket.internal("root", BucketType.ROOT, 1);
        for (int r = 0; r < 3; r++) {
            CrushBucket rack = CrushBucket.internal("rack" + r, BucketType.RACK, 1);
            for (int h = 0; h < 2; h++) {
                CrushBucket host = CrushBucket.internal("rack" + r + "-host" + h, BucketType.HOST, 1);
                int id = r * 10 + h;
                host.addChild(CrushBucket.osd("osd-" + id, OsdId.of(id), 1));
                rack.addChild(host);
            }
            root.addChild(rack);
        }
        Crush crush = new Crush(new CrushMap(root));
        Placement placement = new Placement(8);
        BlockLayer bl = new BlockLayer();
        LeaseService leases = new LeaseService();
        extents = new ExtentService();
        node = new NodeApi(placement, bl, crush, leases, extents, 3);
    }

    @Test
    void putReturnsResult() {
        var r = node.put(ObjectId.of("o1"), new byte[]{1, 2, 3});
        assertThat(r.writtenBytes()).isEqualTo(3);
        assertThat(r.osds()).hasSize(3);
    }

    @Test
    void putIsDeterministicInPg() {
        var r1 = node.put(ObjectId.of("o1"), new byte[]{1});
        var r2 = node.put(ObjectId.of("o1"), new byte[]{2});
        assertThat(r1.pg()).isEqualTo(r2.pg());
    }

    @Test
    void putAppendsToSameExtent() {
        node.put(ObjectId.of("o1"), new byte[]{1, 2});
        node.put(ObjectId.of("o1"), new byte[]{3, 4, 5});
        var ext = extents.get("ext-" + node.put(ObjectId.of("o1"), new byte[]{}).pg().value());
        // After three puts of same key, length should be 5 (extent shared by pg).
        assertThat(ext.length()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void putUsesReplicationFactor() {
        var r = node.put(ObjectId.of("o1"), new byte[]{0});
        assertThat(r.osds()).hasSize(3);
    }

    @Test
    void putToDifferentKeysMayLandInDifferentPgs() {
        int distinctPgs = 0;
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < 32; i++) {
            var r = node.put(ObjectId.of("k-" + i), new byte[]{(byte) i});
            seen.add(r.pg().value());
        }
        assertThat(seen.size()).isGreaterThan(1);
    }

    @Test
    void putGeneratesNonEmptyExtentId() {
        var r = node.put(ObjectId.of("o1"), new byte[]{9});
        assertThat(r.extentId()).startsWith("ext-");
    }
}
