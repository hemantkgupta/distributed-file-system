package com.hkg.dfs.node;

import com.hkg.dfs.common.ChunkId;
import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.OsdId;
import com.hkg.dfs.common.PgId;
import com.hkg.dfs.crush.BucketType;
import com.hkg.dfs.crush.Crush;
import com.hkg.dfs.lease.ExtentService;
import com.hkg.dfs.lease.LeaseService;
import com.hkg.dfs.placement.BlockLayer;
import com.hkg.dfs.placement.PgLocation;
import com.hkg.dfs.placement.Placement;

import java.util.List;

/**
 * Composes CRUSH + Placement + Lease + Extent to provide an end-to-end
 * {@link #put(ObjectId, byte[])}: hash → PG → OSD set → grant primary lease →
 * append to extent → seal.
 */
public final class NodeApi {
    private final Placement placement;
    private final BlockLayer blockLayer;
    private final Crush crush;
    private final LeaseService leases;
    private final ExtentService extents;
    private final int replicationFactor;

    public NodeApi(Placement placement, BlockLayer blockLayer, Crush crush,
                   LeaseService leases, ExtentService extents, int replicationFactor) {
        this.placement = placement;
        this.blockLayer = blockLayer;
        this.crush = crush;
        this.leases = leases;
        this.extents = extents;
        this.replicationFactor = replicationFactor;
    }

    public PutResult put(ObjectId obj, byte[] bytes) {
        PgId pg = placement.objectToPg(obj);
        PgLocation loc = blockLayer.lookup(pg).orElseGet(() -> {
            List<OsdId> osds = crush.place(obj, replicationFactor, BucketType.RACK);
            return blockLayer.putInitial(pg, osds);
        });
        ChunkId chunk = ChunkId.of(Math.abs(obj.value().hashCode()));
        leases.grant(chunk, loc.primary());
        String extentId = "ext-" + pg.value();
        try {
            extents.get(extentId);
        } catch (IllegalStateException missing) {
            extents.open(extentId);
        }
        extents.append(extentId, bytes);
        return new PutResult(pg, loc.osds(), chunk, extentId, bytes.length);
    }

    public record PutResult(PgId pg, List<OsdId> osds, ChunkId chunk, String extentId, int writtenBytes) {}
}
