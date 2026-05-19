# ADR-0004: 15 Modules Split by Architectural Concern, Not by Phase

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

The repo could have organised its code along several axes:

- **By phase** (the build order: foundation, storage backend, control plane, ops). Useful for the blog narrative.
- **By plane** (control plane / data plane). Matches the wiki's primary architectural commitment.
- **By concern** (placement, consistency, durability, QoS, etc.). One module per concern; lets each be tested in isolation.

A single-module monolith is also an option — but loses both teaching value (no clear boundaries) and testability.

## Decision

Use 15 Gradle subprojects, one per architectural concern:

```
Phase 1: dfs-common  dfs-crush  dfs-placement  dfs-lease  dfs-node
Phase 2: dfs-allocator  dfs-storage  dfs-erasure
Phase 3: dfs-mds  dfs-monitor  dfs-qos  dfs-custodian
Phase 4: dfs-simulator  dfs-metrics  dfs-security
```

The phase grouping is the order of authoring (and matches the blog narrative). The module name is the concern. The dependency graph encodes the architectural rules: `dfs-qos` knows nothing about `dfs-custodian`; `dfs-monitor` knows nothing about `dfs-mds`; `dfs-simulator` is the only module that depends on everything.

## Alternatives considered

**Single monolith module.** Faster to author. Loses the strict-isolation property — refactoring one concern (e.g. dmClock) could quietly break unrelated tests. Loses the ability to run one module's tests in isolation.

**Fewer, coarser modules (e.g. 5 — common, foundation, data, control, ops).** Cleaner top-level structure. Loses the "one concern per module" guarantee; e.g. `data` would bundle `dfs-storage` + `dfs-erasure` + `dfs-allocator`, all of which have different responsibilities.

**Module per plane (control / data).** Matches the wiki's primary cleavage. But within a plane there are still 4+ distinct concerns; you'd need sub-packaging anyway. Net: not simpler.

**Module per class (one Gradle subproject per Java class).** Maximally granular. Operationally absurd; Gradle has overhead per subproject.

## Consequences

**Positive:**
- Each module's tests run in isolation. `./gradlew :dfs-crush:test` produces a clean test result without anything else's noise.
- The dependency graph (`build.gradle` `project(':...')` blocks) is the single source of truth for architectural rules. If `dfs-qos` accidentally imports `dfs-custodian`, the build fails.
- Module pages in `docs/modules/` are 1:1 with Gradle subprojects.
- Adding a new concept is straightforward: create a new module, write tests, wire it into the dependency graph.

**Negative:**
- 15 subprojects is more Gradle overhead than 1. Build time is ~15-20 seconds total; faster than most production projects but slower than a monolith.
- Some natural collaborators (e.g. `dfs-allocator` + `dfs-storage`) end up cross-module dependencies. The boundary is intentional but means a small change occasionally touches two modules.
- The `dfs-common` module ends up depended on by 14 others — any change to it forces a full rebuild.

## Implementation pointers

- `settings.gradle` — the list of 15 includes plus inline comments grouping them by phase.
- `build.gradle` — top-level `project(':...')` blocks defining the per-module dependency graph.
- `~/.claude/skills/cse-topic/checklists/repo-planning.md` — the skill rubric that informed this choice.

## Related

- [`architecture.md`](../architecture.md) — the module dependency graph
- [`modules/README.md`](../modules/README.md) — the per-module index
- [ADR-0006](./0006-record-types-for-value-objects.md) — closely related: per-module style conventions
