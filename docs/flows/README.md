# Cross-Module Flows

> Last reconciled with the repo on 2026-05-20.
>
> End-to-end sequences that touch more than one module. The per-module detail lives in [`../modules/`](../modules/); this folder shows how they compose.

## All flows

| Flow | What it shows | Key modules involved |
|---|---|---|
| [`write-path.md`](./write-path.md) | Object PUT: hash → PG → CRUSH on first touch → lease → extent append | `dfs-node`, `dfs-placement`, `dfs-crush`, `dfs-lease` |
| [`repair-on-disk-failure.md`](./repair-on-disk-failure.md) | Heartbeat miss → durability event → Custodian scan → dmClock dispatch | `dfs-monitor`, `dfs-custodian`, `dfs-qos` |
| [`cap-recall.md`](./cap-recall.md) | MDS conflicting open → recall → flush → re-grant | `dfs-mds` |
| [`extent-sealing.md`](./extent-sealing.md) | Primary failure or partition → seal at last committed length → roll forward | `dfs-lease`, `dfs-monitor` |
| [`crypto-shred-erasure.md`](./crypto-shred-erasure.md) | GDPR-style "destroy the key" → ciphertext permanently undecryptable | `dfs-security`, `dfs-storage` |

## How a flow page is structured

Every page follows the same shape:

1. **Why this flow exists** — the architectural problem it solves.
2. **Sequence** — Mermaid diagram or prose+bullet steps.
3. **Step-by-step walkthrough** — numbered steps with file:line pointers and invariants.
4. **Failure modes** — table: step → failure → behaviour.
5. **Related** — wiki pages, module pages, ADRs.

When adding a flow, copy this structure. When updating an existing flow, keep the structure stable.

## What flows are deliberately NOT documented here

- **Single-module behavior** — that lives in the module page under §4 Internal structure.
- **Operational procedures** — there are no real operations in this teaching repo. The closest thing is the simulator's failure scenarios.
- **Algorithms** — the wiki concept pages cover those.
