package com.hkg.dfs.custodian;

import com.hkg.dfs.common.PgId;

/** A single repair/scrub/rebalance task. */
public record WorkItem(PgId pg, PriorityClass priority, String description) {
    public WorkItem {
        if (pg == null) throw new IllegalArgumentException("pg");
        if (priority == null) throw new IllegalArgumentException("priority");
        if (description == null) description = "";
    }
}
