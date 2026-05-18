package com.hkg.dfs.security;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.TenantId;

/** Data-Unique-Key identifier. */
public record DukId(TenantId tenant, ObjectId object) {
    public DukId {
        if (tenant == null) throw new IllegalArgumentException("tenant");
        if (object == null) throw new IllegalArgumentException("object");
    }
}
