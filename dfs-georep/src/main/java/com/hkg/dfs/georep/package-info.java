/**
 * Async geo-replication service for the DFS cluster.
 *
 * <p>Two roles per region: a {@code Shipper} that watches for sealed extents and journal
 * records and streams them across a pluggable WAN channel, and an {@code Applier} that
 * receives them on the target side and writes them locally with idempotent-apply semantics.
 *
 * <p>See {@code SPEC.md} in this module's root for the implementation contract.
 * Maps to <strong>§9 Geo-Replication Service (Async Log Shipper)</strong> in the service catalog.
 *
 * <p>Status: spec only — implementation has not yet been generated against {@code SPEC.md}.
 */
package com.hkg.dfs.georep;
