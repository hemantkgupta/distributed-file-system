package com.hkg.dfs.crush;

import com.hkg.dfs.common.OsdId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in a CRUSH hierarchy. Internal buckets (ROOT/ROW/RACK/HOST) hold
 * child buckets; leaf buckets (OSD) carry an {@link OsdId}.
 * Implements the hierarchical bucket walk from
 * {@code wiki/concepts/crush-placement-algorithm}.
 */
public final class CrushBucket {
    private final String name;
    private final BucketType type;
    private final int weight;
    private final OsdId osdId; // non-null iff leaf
    private final List<CrushBucket> children = new ArrayList<>();

    private CrushBucket(String name, BucketType type, int weight, OsdId osdId) {
        this.name = name;
        this.type = type;
        this.weight = weight;
        this.osdId = osdId;
    }

    public static CrushBucket internal(String name, BucketType type, int weight) {
        if (type == BucketType.OSD) {
            throw new IllegalArgumentException("internal cannot be OSD");
        }
        return new CrushBucket(name, type, weight, null);
    }

    public static CrushBucket osd(String name, OsdId osdId, int weight) {
        return new CrushBucket(name, BucketType.OSD, weight, osdId);
    }

    public CrushBucket addChild(CrushBucket child) {
        if (type == BucketType.OSD) {
            throw new IllegalStateException("OSD bucket cannot have children");
        }
        children.add(child);
        return this;
    }

    public String name() { return name; }
    public BucketType type() { return type; }
    public int weight() { return weight; }
    public OsdId osdId() { return osdId; }
    public List<CrushBucket> children() { return Collections.unmodifiableList(children); }
    public boolean isLeaf() { return type == BucketType.OSD; }
}
