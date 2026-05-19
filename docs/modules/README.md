# Module Reference

> Last reconciled with the repo on 2026-05-20.
>
> One page per Gradle module. Every page follows the same 7-section template so a reader knows where to look for any specific kind of information.

## All modules

| Phase | Module | Role | Source files | Tests |
|---|---|---|---:|---:|
| 1 | [`dfs-common`](./dfs-common.md) | Shared value types (IDs, generations, bytes) | 8 | 22 |
| 1 | [`dfs-crush`](./dfs-crush.md) | CRUSH placement function + straw selector | 5 | 18 |
| 1 | [`dfs-placement`](./dfs-placement.md) | Block Layer KV lookup + PG hash | 3 | 14 |
| 1 | [`dfs-lease`](./dfs-lease.md) | Chunk leases + extent sealing | 5 | 22 |
| 1 | [`dfs-node`](./dfs-node.md) | End-to-end `put(obj, bytes)` composition | 1 | 6 |
| 2 | [`dfs-allocator`](./dfs-allocator.md) | Bitmap allocator (L0/L1 cascade) | 2 | 13 |
| 2 | [`dfs-storage`](./dfs-storage.md) | BlueStore-style OSD: CoW + WAL deferred | 2 | 18 |
| 2 | [`dfs-erasure`](./dfs-erasure.md) | Replication + Reed-Solomon stub + LRC cost | 3 | 22 |
| 3 | [`dfs-mds`](./dfs-mds.md) | Capability vectors + dynamic subtree partitioning | 5 | 22 |
| 3 | [`dfs-monitor`](./dfs-monitor.md) | Cluster map, heartbeats, durability events | 3 | 12 |
| 3 | [`dfs-qos`](./dfs-qos.md) | dmClock scheduler (R/W/L tags) | 2 | 17 |
| 3 | [`dfs-custodian`](./dfs-custodian.md) | Background work scanner + dispatcher | 5 | 18 |
| 4 | [`dfs-simulator`](./dfs-simulator.md) | End-to-end cluster simulator | 1 | 10 |
| 4 | [`dfs-metrics`](./dfs-metrics.md) | Counters, gauges, histograms + Prometheus | 4 | 15 |
| 4 | [`dfs-security`](./dfs-security.md) | KMS for crypto-shredding (AES-GCM) | 3 | 10 |

Total: **239 tests** across 15 modules.

## Page structure

Every module page has the same 7 sections, in this order:

1. **Role** — what this module owns and what it doesn't.
2. **Wiki anchor** — the primary wiki concept page this module implements (with link).
3. **Public API surface** — the classes a caller actually uses, with method signatures.
4. **Internal structure** — the 2-5 collaborators inside the module and why each exists.
5. **Key tests** — 3-5 representative test cases that demonstrate the contract.
6. **Where it fits** — upstream consumers + downstream dependencies + the dependency rule it enforces.
7. **Stubs and departures from production** — honest call-out of where the code is a teaching simplification.

Add new modules by copying this template. Update existing pages by keeping the structure stable — readers depend on knowing where to look.

## Concept → module lookup

Reverse index: "I want to see how concept X is implemented." Find the row, click through.

| Wiki concept | Module | Primary class |
|---|---|---|
| CRUSH placement algorithm | `dfs-crush` | `Crush` |
| Hybrid deterministic + lookup placement | `dfs-placement` | `Placement` + `BlockLayer` |
| Chunk lease | `dfs-lease` | `LeaseService` |
| Extent sealing | `dfs-lease` | `ExtentService` |
| Bitmap allocator | `dfs-allocator` | `BitmapAllocator` |
| Raw-block user-space storage | `dfs-storage` | `Osd` |
| Erasure coding (replication / RS / LRC) | `dfs-erasure` | `Replication`, `ReedSolomon`, `LRC` |
| Capability vector | `dfs-mds` | `CapabilityVector` |
| Dynamic subtree partitioning | `dfs-mds` | `SubtreePartitioner` |
| Cluster monitor + durability events | `dfs-monitor` | `Monitor`, `DurabilityEvent` |
| dmClock QoS | `dfs-qos` | `DmClockScheduler` |
| Custodian background control plane | `dfs-custodian` | `Custodian`, `RepairScanner` |
| Crypto-shredding (key destruction) | `dfs-security` | `Kms` |

## The 7-section template (for reference when authoring)

```markdown
# <module-name>

> Last reconciled with the repo on YYYY-MM-DD.

## 1. Role
One paragraph: what this module owns; what it doesn't.

## 2. Wiki anchor
The primary wiki page this module implements, with a link.

## 3. Public API surface
Code blocks with class + method signatures. Cite file paths.

## 4. Internal structure
The 2-5 internal collaborators. One paragraph each.

## 5. Key tests
A table or short list naming 3-5 tests that demonstrate the contract.

## 6. Where it fits
Upstream consumers + downstream dependencies + which dependency rule this module enforces.

## 7. Stubs and departures from production
Honest: what's simpler than reality, and why.
```
