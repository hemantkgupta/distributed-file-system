# Architecture Decision Records

> Each ADR captures one significant design decision for this teaching repo: the forcing function, what was chosen, what was rejected, and what each choice commits us to.

## All ADRs

| ID | Title | Status | Date | One-line summary |
|---|---|---|---|---|
| [0001](./0001-pure-java-no-jni.md) | Pure-Java implementations, no JNI | Accepted | 2026-05-20 | JDK-only deps; build with just Java 17 + Gradle. |
| [0002](./0002-in-memory-substrates.md) | In-memory substrates instead of embedded RocksDB | Accepted | 2026-05-20 | CHM / CSLM stand in for RocksDB / Bigtable; teaching > fidelity. |
| [0003](./0003-xor-parity-stub-not-galois.md) | XOR-based parity stub, not full Galois-field RS | Accepted | 2026-05-20 | RS encode/decode SHAPE without GF(2^8) math. |
| [0004](./0004-15-modules-by-concern.md) | 15 modules split by architectural concern | Accepted | 2026-05-20 | One Gradle subproject per concern; dependency graph = architectural rules. |
| [0005](./0005-aes-gcm-real-crypto.md) | Real AES-GCM crypto, not a stub | Accepted | 2026-05-20 | The one place stubs would teach the wrong lesson. |
| [0006](./0006-record-types-for-value-objects.md) | Java `record` for value objects | Accepted | 2026-05-20 | Compact, immutable, free `equals`/`hashCode`. |

## When to write a new ADR for this repo

Write one when you're about to:

- Add a JNI / native dependency (would reverse ADR-0001).
- Replace an in-memory substrate with a real persistence layer (would reverse ADR-0002).
- Implement true Galois-field Reed-Solomon (would reverse ADR-0003).
- Restructure the module taxonomy (would reverse or amend ADR-0004).
- Add a new architectural concept that doesn't fit the existing modules.
- Make a security-boundary choice (encryption scheme, key management).

Don't write one for:

- New tests, new private helper methods.
- Naming or style changes within a module.
- Refactors that preserve the module's public API.

## ADR shape

Every ADR follows the same template:

```markdown
# ADR-NNNN: <Decision name>

**Status**: Accepted | Superseded by NNNN
**Date**: YYYY-MM-DD
**Deciders**: ...

## Context
The forcing function.

## Decision
The thing chosen, named precisely.

## Alternatives considered
Each with a 2-3 sentence "why rejected".

## Consequences
**Positive:** what this commits us to that is good.
**Negative:** what gets harder.

## Implementation pointers
File paths.

## Related
Wiki, module pages, other ADRs.
```

When superseding an older ADR, keep the older file but mark its Status `Superseded by NNNN`. Cross-link both directions.

## Decisions worth ADRs but not yet captured

The following choices exist in code but aren't formalised:

- **`record` validation in compact constructors** vs static factory methods. (ADR-0006 mentions but doesn't expand.)
- **`synchronized` on every public method of `Osd` and `BitmapAllocator`**, vs read-write locks. The simpler answer wins here, but the choice could be revisited under contention.
- **Static `QosClass` factory constants in `Custodian`** (`CRITICAL_QOS`, etc.) vs configuration-driven class lists. Static keeps the test path simple; production would need configurability.
- **Phase-as-comment-section in `settings.gradle`** vs phase-as-subdirectory. Comments win because they survive Gradle's flat-include convention.
