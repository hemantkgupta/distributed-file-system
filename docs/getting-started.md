# First 30 minutes with this repo

> A guided tour for a Principal Engineer who just cloned the repo. The goal is the mental model, not the stopwatch.
>
> Last reconciled with the repo on 2026-05-20.

## What you'll have at the end of 30 minutes

- A green build (`./gradlew build`).
- A clear picture of which Java module implements which wiki concept.
- A walkthrough of one module's tests so you can see the contract being exercised.
- A short list of where to read next.

## 5 minutes — what is this?

This repo is the Java 17 companion implementation for the CSE wiki topic **Ceph/GFS-class distributed file systems**. The two blog posts are at `~/CSE-Raw/raw-blog/distributed-file-system.md` (standard, ~3.6k words) and `~/CSE-Raw/raw-blog/distributed-file-system-full.md` (full, ~13k words). The architectural source of truth is the design walkthrough at `~/CSE-Raw/wiki/my-explanations/design-distributed-file-system.md`.

The repo is structured as a Gradle multi-module project with 15 modules across 4 phases:

```
Phase 1 — Foundation:           dfs-common, dfs-crush, dfs-placement, dfs-lease, dfs-node
Phase 2 — Storage backend:      dfs-allocator, dfs-storage, dfs-erasure
Phase 3 — Control plane:        dfs-mds, dfs-monitor, dfs-qos, dfs-custodian
Phase 4 — Ops + simulator:      dfs-simulator, dfs-metrics, dfs-security
                                + deploy/k8s/
```

Every module is small, single-concept, and has its own `src/test/` with contract-style tests. There are 239 tests total across the repo.

## 10 minutes — build, test, explore

### Prerequisites

- JDK 17+ (the repo pins `sourceCompatibility = 17` in `build.gradle`).
- `jenv` recommended for JDK version selection.

### Steps

```sh
cd ~/code-all/distributed-file-system

# 1) Full build — all 15 modules, all 239 tests.
./gradlew build --console=plain

# 2) Run one module's tests in isolation.
./gradlew :dfs-crush:test

# 3) See where the tests live.
find dfs-crush/src/test -name "*Test.java"

# 4) Per-module test counts.
find . -name "TEST-*.xml" -path "*/build/test-results/test/*" | while read f; do
  mod=$(echo "$f" | sed -E 's|^\./([^/]+)/.*|\1|')
  n=$(grep -oE 'tests="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+')
  echo "$n $mod"
done | awk '{m[$2]+=$1; total+=$1} END {for (k in m) printf "%-25s %d\n", k, m[k]; printf "%-25s %d\n", "TOTAL", total}' | sort
```

Expected: `TOTAL 239`.

## 10 minutes — read one module top-to-bottom

The recommended starting point is **`dfs-crush`** — small enough to read in 10 minutes, central enough to anchor the mental model.

```sh
# The public API surface (3 source files).
ls dfs-crush/src/main/java/com/hkg/dfs/crush/

# Read the entry point.
less dfs-crush/src/main/java/com/hkg/dfs/crush/Crush.java

# Then the straw selector that gives CRUSH its key property.
less dfs-crush/src/main/java/com/hkg/dfs/crush/StrawSelector.java

# Tests demonstrate the contract.
less dfs-crush/src/test/java/com/hkg/dfs/crush/CrushTest.java
```

The wiki concept this implements: [`wiki/concepts/crush-placement-algorithm`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/crush-placement-algorithm.md). The module's full design notes are at [`modules/dfs-crush.md`](./modules/dfs-crush.md).

## 5 minutes — the mental model

```
         ┌────────────────────────────────────────────┐
         │              dfs-common                    │
         │   ObjectId, ChunkId, PgId, OsdId, Bytes    │
         └─────────────┬───────────────┬──────────────┘
                       │               │
            ┌──────────▼─────┐  ┌─────▼──────────┐
            │   dfs-crush    │  │ dfs-placement  │
            │  (CRUSH walk)  │  │  (PG lookup)   │
            └──────────┬─────┘  └─────┬──────────┘
                       │              │
                       ▼              ▼
                  ┌──────────────────────┐
                  │      dfs-lease       │
                  │ leases + extents     │
                  └──────────┬───────────┘
                             │
                             ▼
                       ┌───────────┐
                       │  dfs-node │  ← end-to-end PUT entry point
                       └───────────┘

   Storage backend (Phase 2)              Control plane (Phase 3)
   ┌──────────────────────┐               ┌─────────────────────────┐
   │  dfs-allocator       │               │  dfs-mds                │
   │  dfs-storage         │               │  dfs-monitor            │
   │  dfs-erasure         │               │  dfs-qos                │
   └──────────────────────┘               │  dfs-custodian          │
                                          └─────────────────────────┘

   Ops + simulator + security (Phase 4)
   ┌──────────────────────────────────────────────────────────────────┐
   │  dfs-simulator (composes everything)                             │
   │  dfs-metrics (counters / gauges / histograms / Prometheus)       │
   │  dfs-security (KMS for crypto-shredding)                         │
   └──────────────────────────────────────────────────────────────────┘
```

Dependency arrows flow downward only. Each module depends on `dfs-common` plus a small set of its phase peers. `dfs-custodian` depends on `dfs-monitor` and `dfs-qos`; `dfs-qos` does not know `dfs-custodian` exists — which is what makes each module testable in isolation.

## What to read next — choose your path

| Goal | Read |
|---|---|
| Architecture in depth | [`architecture.md`](./architecture.md) |
| Map a wiki concept to its module | [`modules/README.md`](./modules/README.md) |
| Trace an end-to-end PUT | [`flows/write-path.md`](./flows/write-path.md) |
| Understand a specific module | [`modules/<m>.md`](./modules/) |
| Understand why a design choice was made | [`decisions/README.md`](./decisions/README.md) |
| Look up a term | [`glossary.md`](./glossary.md) |

## Honesty about what this code is and isn't

- **It is**: a teaching artifact. The code follows the wiki's architectural decisions faithfully but uses in-memory substrates instead of real disks, RocksDB, etc. Every public class has Javadoc naming the wiki concept it implements.
- **It is not**: a production distributed file system. Several deliberate stubs:
  - `dfs-storage` uses `ConcurrentSkipListMap<String, byte[]>` instead of RocksDB-on-BlueFS.
  - `dfs-erasure` uses XOR-based parity instead of true Galois-field Reed-Solomon.
  - `dfs-monitor` is in-memory rather than Paxos-replicated.
  - `dfs-mds` doesn't persist to a transactional KV store.

The reasons live in [`decisions/`](./decisions/).
