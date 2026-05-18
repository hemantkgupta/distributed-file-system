package com.hkg.dfs.mds;

import java.util.Set;

/**
 * A bitfield of access rights the MDS hands a client for an open inode.
 * Equivalent to Ceph's {@code Fr / Fw / Fc / Fb} cap flags. Implements
 * {@code wiki/concepts/capability-vector}.
 */
public record CapabilityVector(Set<Cap> caps) {
    public enum Cap { READ, WRITE, CACHE_READ, CACHE_WRITE, EXCLUSIVE }

    public CapabilityVector {
        if (caps == null) throw new IllegalArgumentException("caps");
        caps = Set.copyOf(caps);
    }

    public boolean hasRead() { return caps.contains(Cap.READ); }
    public boolean hasWrite() { return caps.contains(Cap.WRITE); }
    public boolean hasExclusive() { return caps.contains(Cap.EXCLUSIVE); }

    public static CapabilityVector readOnly() {
        return new CapabilityVector(Set.of(Cap.READ, Cap.CACHE_READ));
    }

    public static CapabilityVector readWrite() {
        return new CapabilityVector(Set.of(Cap.READ, Cap.WRITE, Cap.CACHE_READ, Cap.CACHE_WRITE));
    }

    public static CapabilityVector exclusive() {
        return new CapabilityVector(Set.of(Cap.READ, Cap.WRITE, Cap.CACHE_READ, Cap.CACHE_WRITE, Cap.EXCLUSIVE));
    }
}
