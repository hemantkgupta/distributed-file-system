package com.hkg.dfs.mds;

/** Filesystem inode: id + parent + name + type. */
public record Inode(long inodeId, long parentId, String name, InodeType type) {
    public Inode {
        if (inodeId < 0) throw new IllegalArgumentException("inodeId");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (type == null) throw new IllegalArgumentException("type");
    }
}
