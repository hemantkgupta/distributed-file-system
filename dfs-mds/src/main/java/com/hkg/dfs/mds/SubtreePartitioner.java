package com.hkg.dfs.mds;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic subtree partitioning across MDS shards. Each path prefix is owned
 * by exactly one MDS. Migrations are explicit: {@link #migrate(String, int, int)}.
 */
public final class SubtreePartitioner {
    private final Map<String, Integer> ownership = new HashMap<>();

    public synchronized void assign(String subtree, int mdsId) {
        if (subtree == null || subtree.isBlank()) throw new IllegalArgumentException("subtree");
        if (mdsId < 0) throw new IllegalArgumentException("mdsId");
        ownership.put(subtree, mdsId);
    }

    public synchronized Optional<Integer> ownerOf(String path) {
        String best = null;
        for (String k : ownership.keySet()) {
            if (path.startsWith(k)) {
                if (best == null || k.length() > best.length()) best = k;
            }
        }
        return best == null ? Optional.empty() : Optional.of(ownership.get(best));
    }

    public synchronized void migrate(String subtree, int fromMds, int toMds) {
        Integer cur = ownership.get(subtree);
        if (cur == null) throw new IllegalStateException("no owner for " + subtree);
        if (cur != fromMds) {
            throw new IllegalStateException("subtree owner is " + cur + " not " + fromMds);
        }
        ownership.put(subtree, toMds);
    }

    public synchronized int subtreeCount() {
        return ownership.size();
    }
}
