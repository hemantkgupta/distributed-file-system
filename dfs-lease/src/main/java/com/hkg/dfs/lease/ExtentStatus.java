package com.hkg.dfs.lease;

/** Lifecycle of an extent (append-only block of chunks). */
public enum ExtentStatus {
    OPEN, SEALED
}
