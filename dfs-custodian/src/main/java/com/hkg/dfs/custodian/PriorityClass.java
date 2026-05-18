package com.hkg.dfs.custodian;

/** Priority bands for control-loop work, highest first. */
public enum PriorityClass {
    CRITICAL_REPAIR,
    ROUTINE_REPAIR,
    DEEP_SCRUB,
    SHALLOW_SCRUB,
    REBALANCE,
    TIER_TRANSITION
}
