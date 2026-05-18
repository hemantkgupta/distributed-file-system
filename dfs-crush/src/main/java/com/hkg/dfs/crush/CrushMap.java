package com.hkg.dfs.crush;

/** Root holder for a CRUSH hierarchy. */
public final class CrushMap {
    private final CrushBucket root;

    public CrushMap(CrushBucket root) {
        if (root == null || root.type() != BucketType.ROOT) {
            throw new IllegalArgumentException("root must be ROOT");
        }
        this.root = root;
    }

    public CrushBucket root() { return root; }
}
