# distributed-file-system — documentation hub

> **Canonical narrative:** the blog posts in `~/CSE-Raw/raw-blog/distributed-file-system.md` and `~/CSE-Raw/raw-blog/distributed-file-system-full.md`. The blogs explain the architecture from first principles. This `docs/` folder describes what is *actually built* in this Java companion repo and maps every wiki concept to a file.
>
> Last reconciled with the repo on 2026-05-20.

## Pick your path

| If you want to … | Start with | Then |
|---|---|---|
| Understand what this repo IS in 5 minutes | [`getting-started.md`](./getting-started.md) → [`architecture.md`](./architecture.md) | the standard blog |
| Build, run tests, and explore one module | [`getting-started.md`](./getting-started.md) | the module page for your area of interest |
| Trace a write end-to-end through the modules | [`flows/write-path.md`](./flows/write-path.md) | [`modules/dfs-node.md`](./modules/dfs-node.md) + downstream module pages |
| Map a wiki concept to code | [`modules/README.md`](./modules/README.md) (concept → module table) | the specific module page |
| Find the file that implements something the blog mentions | [`code-companion.md`](./code-companion.md) | the per-module §3 surface |
| Understand a specific module's contract | [`modules/<module>.md`](./modules/) | its tests under `<module>/src/test/` |
| Understand why a particular design choice was made | [`decisions/README.md`](./decisions/README.md) | the relevant ADR |
| Look up an unfamiliar term | [`glossary.md`](./glossary.md) | the linked wiki concept |
| Read about a sequence (repair, scrub, cap recall) | [`flows/README.md`](./flows/README.md) | the involved modules |

## The doc tree

### Reference

- [`getting-started.md`](./getting-started.md) — first 30 minutes: build, test, explore.
- [`architecture.md`](./architecture.md) — the big picture: module dependency graph, how the wiki's architectural decisions map to packages.
- [`glossary.md`](./glossary.md) — vocabulary lookup. Links each term to its wiki concept and the module that implements it.
- [`code-companion.md`](./code-companion.md) — per-blog-part map: every section of the standard blog → modules + key classes + matching module page; explicit gaps where the blog claims a mechanism the code does not implement.

### Modules — [`modules/`](./modules/)

One page per module. Every page follows a strict 7-section template: Role → Wiki anchor → Public API surface → Internal structure → Key tests → Where it fits → Stubs and departures from production.

15 modules across 4 phases. See [`modules/README.md`](./modules/README.md) for the full index and the concept → module table.

| Phase | Modules |
|---|---|
| 1 — Foundation | `dfs-common`, `dfs-crush`, `dfs-placement`, `dfs-lease`, `dfs-node` |
| 2 — Storage backend | `dfs-allocator`, `dfs-storage`, `dfs-erasure` |
| 3 — Control plane | `dfs-mds`, `dfs-monitor`, `dfs-qos`, `dfs-custodian` |
| 4 — Ops + simulator + security | `dfs-simulator`, `dfs-metrics`, `dfs-security` |

### Flows — [`flows/`](./flows/)

End-to-end sequences crossing multiple modules. Mermaid sequence diagrams + step walkthroughs + failure modes. See [`flows/README.md`](./flows/README.md) for the index.

| Flow | What it shows |
|---|---|
| [`write-path.md`](./flows/write-path.md) | Object PUT: hash → PG → CRUSH → lease → extent append |
| [`repair-on-disk-failure.md`](./flows/repair-on-disk-failure.md) | Monitor heartbeat miss → durability event → Custodian → dmClock → repair |
| [`cap-recall.md`](./flows/cap-recall.md) | MDS conflicting access → recall → flush → re-grant |
| [`extent-sealing.md`](./flows/extent-sealing.md) | Primary failure → seal → allocate new extent |
| [`crypto-shred-erasure.md`](./flows/crypto-shred-erasure.md) | GDPR delete: destroy DUK → ciphertext undecryptable |

### Decisions — [`decisions/`](./decisions/)

Architecture Decision Records. Why each load-bearing choice was made — and what alternatives were rejected. See [`decisions/README.md`](./decisions/README.md) for the index.

| ADR | Title |
|---|---|
| [0001](./decisions/0001-pure-java-no-jni.md) | Pure-Java implementations, no JNI |
| [0002](./decisions/0002-in-memory-substrates.md) | In-memory substrates instead of embedded RocksDB |
| [0003](./decisions/0003-xor-parity-stub-not-galois.md) | XOR-based parity stub, not full Galois-field Reed-Solomon |
| [0004](./decisions/0004-15-modules-by-concern.md) | 15 modules split by architectural concern, not by phase |
| [0005](./decisions/0005-aes-gcm-real-crypto.md) | Real AES-GCM crypto, not a stub |
| [0006](./decisions/0006-record-types-for-value-objects.md) | Java `record` for value objects |

## How docs and code stay in sync

The **mapping rule**: every wiki concept page that this code implements must have exactly one module page that names it as its primary anchor. Every module page must declare the wiki concept it implements in its "Wiki anchor" section. If the code adds a behavior the wiki doesn't yet describe, file an issue against the wiki; if the wiki has a concept this code doesn't implement, the module page's "Stubs and departures" section says so.

Two failure modes the rule protects against:

1. The wiki describes a mechanism that has no code (false advertising in the blog).
2. The code does something the wiki never mentions (orphaned behavior with no architectural reasoning).

When changing module code, update the matching `modules/<m>.md` and any `flows/*.md` that touch it. New design decision? Add an ADR in `decisions/`.

## Running the code

```sh
./gradlew build           # all modules, all tests
./gradlew :dfs-crush:test # one module
```

The repo has no external runtime dependencies — every "service" is in-process, in-memory, and pure-Java. See [`getting-started.md`](./getting-started.md) for the full walkthrough.
