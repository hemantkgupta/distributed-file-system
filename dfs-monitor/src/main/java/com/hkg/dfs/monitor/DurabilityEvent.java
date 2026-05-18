package com.hkg.dfs.monitor;

import com.hkg.dfs.common.PgId;

/** Emitted when a PG's effective replica count drops below the floor. */
public record DurabilityEvent(PgId pg, int currentReplicas, int floor) { }
