package com.hkg.dfs.crush;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.OsdId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Top-level CRUSH placement API. Maps an {@link ObjectId} to a deterministic
 * list of distinct OSDs honouring a failure-domain constraint
 * (replicas in different RACKs/HOSTs).
 * <p>Implements {@code wiki/concepts/crush-placement-algorithm}.
 */
public final class Crush {
    private final CrushMap map;
    private final StrawSelector selector = new StrawSelector();

    public Crush(CrushMap map) {
        this.map = Objects.requireNonNull(map, "map");
    }

    public List<OsdId> place(ObjectId obj, int replicationFactor, BucketType failureDomain) {
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor>0");
        }
        long seed = obj.value().hashCode();
        List<OsdId> result = new ArrayList<>();
        Set<String> usedFailureDomains = new HashSet<>();
        Set<OsdId> usedOsds = new HashSet<>();
        int attempt = 0;
        int maxAttempts = replicationFactor * 512;
        while (result.size() < replicationFactor && attempt < maxAttempts) {
            long pertSeed = seed + attempt * 0x9E3779B97F4A7C15L;
            CrushBucket fd = walkAvoidingUsed(map.root(), pertSeed, attempt, failureDomain, usedFailureDomains);
            if (fd != null && !usedFailureDomains.contains(fd.name())) {
                CrushBucket leaf = walkToLeafAvoidingUsed(fd, pertSeed, attempt, usedOsds);
                if (leaf != null && !usedOsds.contains(leaf.osdId())) {
                    result.add(leaf.osdId());
                    usedFailureDomains.add(fd.name());
                    usedOsds.add(leaf.osdId());
                }
            }
            attempt++;
        }
        if (result.size() < replicationFactor) {
            throw new IllegalStateException("could not satisfy replication factor "
                    + replicationFactor + " under failure domain " + failureDomain);
        }
        return result;
    }

    private CrushBucket walkAvoidingUsed(CrushBucket node, long seed, int replicaIdx,
                                         BucketType failureDomain, Set<String> usedFds) {
        CrushBucket cur = node;
        while (cur != null && cur.type() != failureDomain && !cur.isLeaf()) {
            List<CrushBucket> candidates = new ArrayList<>();
            for (CrushBucket c : cur.children()) {
                if (c.type() == failureDomain && usedFds.contains(c.name())) continue;
                candidates.add(c);
            }
            if (candidates.isEmpty()) candidates = cur.children();
            cur = selector.select(candidates, seed + replicaIdx, replicaIdx);
        }
        return cur;
    }

    private CrushBucket walkToLeafAvoidingUsed(CrushBucket node, long seed, int replicaIdx,
                                               Set<OsdId> usedOsds) {
        CrushBucket cur = node;
        while (cur != null && !cur.isLeaf()) {
            List<CrushBucket> candidates = new ArrayList<>();
            for (CrushBucket c : cur.children()) {
                if (c.isLeaf() && usedOsds.contains(c.osdId())) continue;
                candidates.add(c);
            }
            if (candidates.isEmpty()) candidates = cur.children();
            cur = selector.select(candidates, seed + replicaIdx * 1024L, replicaIdx);
        }
        return cur;
    }
}
