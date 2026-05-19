package com.hkg.dfs.mds;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MDS cluster with per-path capability tracking and a simple inode cache.
 * The cluster is split into MDS shards by {@link SubtreePartitioner}.
 * Implements the dynamic-subtree partitioning + cap recall pattern from
 * {@code wiki/my-explanations/design-distributed-file-system}.
 */
public final class MdsCluster {
    private final ConcurrentHashMap<String, Inode> inodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CapHolder> caps = new ConcurrentHashMap<>();
    private final AtomicLong inodeSeq = new AtomicLong(1);
    private final SubtreePartitioner partitioner = new SubtreePartitioner();

    public Inode mkdir(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path");
        long parentId = checkParentAndGetId(path);
        long id = inodeSeq.getAndIncrement();
        Inode inode = new Inode(id, parentId, nameOf(path), InodeType.DIR);
        inodes.put(path, inode);
        return inode;
    }

    public Inode create(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path");
        long parentId = checkParentAndGetId(path);
        long id = inodeSeq.getAndIncrement();
        Inode inode = new Inode(id, parentId, nameOf(path), InodeType.FILE);
        inodes.put(path, inode);
        return inode;
    }

    public Optional<Inode> stat(String path) {
        return Optional.ofNullable(inodes.get(path));
    }

    /**
     * Issue a capability vector for the given path to {@code clientId}.
     * If another client holds a conflicting (write/exclusive) cap, recall
     * it first.
     */
    public synchronized CapabilityVector open(String path, String clientId, CapabilityVector requested) {
        CapHolder existing = caps.get(path);
        if (existing != null && !existing.clientId.equals(clientId) && conflicts(existing.cap, requested)) {
            recall(path);
        }
        caps.put(path, new CapHolder(clientId, requested));
        return requested;
    }

    public void recall(String path) {
        caps.remove(path);
    }

    public Optional<CapabilityVector> heldBy(String path, String clientId) {
        CapHolder h = caps.get(path);
        if (h == null || !h.clientId.equals(clientId)) return Optional.empty();
        return Optional.of(h.cap);
    }

    public SubtreePartitioner partitioner() {
        return partitioner;
    }

    public Map<String, Inode> snapshot() {
        return Map.copyOf(inodes);
    }

    public void rename(String fromPath, String toPath) {
        if (toPath == null || !toPath.startsWith("/")) throw new IllegalArgumentException("toPath");
        Inode prev = inodes.remove(fromPath);
        if (prev == null) throw new IllegalStateException("missing: " + fromPath);
        long parentId = checkParentAndGetId(toPath);
        Inode renamed = new Inode(prev.inodeId(), parentId, nameOf(toPath), prev.type());
        inodes.put(toPath, renamed);
        // capability invalidated by rename
        caps.remove(fromPath);
    }

    private long checkParentAndGetId(String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return 0;
        String parentPath = path.substring(0, slash);
        Inode parent = inodes.get(parentPath);
        if (parent == null) {
            throw new IllegalStateException("parent directory missing: " + parentPath);
        }
        if (parent.type() != InodeType.DIR) {
            throw new IllegalStateException("parent is not a directory: " + parentPath);
        }
        return parent.inodeId();
    }

    private String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    private boolean conflicts(CapabilityVector held, CapabilityVector wanted) {
        return held.hasExclusive() || wanted.hasExclusive() || wanted.hasWrite() || held.hasWrite();
    }

    private record CapHolder(String clientId, CapabilityVector cap) {}
}
