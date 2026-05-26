/**
 * Thick client library for the DFS cluster.
 *
 * <p>In-process layer that translates application I/O into MDS RPCs, block-layer
 * lookups, and direct OSD reads/writes. Holds four caches (capability,
 * cluster-map, write buffer, page cache) and a scatter-gather coordinator so the
 * common-path read or write never traverses a proxy hop.
 *
 * <p>See {@code SPEC.md} in this module's root for the implementation contract.
 * Maps to <strong>§6 Client Library (Thick Client)</strong> in the service catalog.
 *
 * <p>Status: spec only — implementation has not yet been generated against {@code SPEC.md}.
 */
package com.hkg.dfs.client;
