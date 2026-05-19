# dfs-qos

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The dmClock multi-tenant proportional-share I/O scheduler. Each "class" of operations (tenant, priority band, background-vs-foreground) gets three numerical tags — **reservation** (guaranteed minimum), **weight** (proportional share of excess), **limit** (hard ceiling). The scheduler picks ops in a two-phase walk: reservation phase first, weight phase second, never exceeding any class's limit.

This is the wire on which `dfs-custodian` runs background work without starving client I/O.

## 2. Wiki anchor

[`wiki/concepts/dmclock-qos`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/dmclock-qos.md).

## 3. Public API surface

```java
package com.hkg.dfs.qos;

public final class DmClockScheduler {
    public synchronized void addClass(QosClass cls);
    public synchronized CompletableFuture<Void> submit(String className, Runnable op);
    public synchronized String dispatch();        // returns class name served, or null
    public synchronized long served(String className);
    public synchronized int queueDepth(String className);
}

public record QosClass(String name, double reservation, double weight, double limit) {}
```

Source: `dfs-qos/src/main/java/com/hkg/dfs/qos/DmClockScheduler.java`.

## 4. Internal structure

- **`classes`** — `LinkedHashMap<String, QosClass>` preserving registration order.
- **`queues`** — per-class `ArrayDeque<Op>`. `Op` is a private record bundling the runnable + the completion future.
- **`tags`** — per-class `Tags(r, w, l)` — the three virtual-time tags. Updated on each `submit`:
  ```java
  tags.put(name, new Tags(
      Math.max(t.r, now) + (cls.reservation() > 0 ? 1.0 / cls.reservation() : POSITIVE_INFINITY),
      Math.max(t.w, now) + 1.0 / cls.weight(),
      Math.max(t.l, now) + 1.0 / cls.limit()
  ));
  ```
- **`virtualTime`** — advances by 1.0 on each `dispatch` call.

The `dispatch` method runs two phases:

1. **Reservation phase**: pick the class with the smallest `r` tag where `r <= virtualTime` AND its queue is non-empty.
2. **Weight phase**: if no class qualifies in reservation phase, pick the class with the smallest `w` tag where its `l` tag has not yet passed (i.e., not over-limit) AND its queue is non-empty.

If both phases fail (every class is over-limit or has an empty queue), return `null`.

The mathematics is standard "tag-based proportional share scheduling" from the dmClock paper. The key invariant: classes with non-zero reservation are guaranteed dispatch rate ≥ reservation regardless of other classes' load.

## 5. Key tests

17 tests in `DmClockSchedulerTest`.

| Test | Demonstrates |
|---|---|
| `duplicateClassRejected` | `addClass` rejects a name that's already registered. |
| `submitToUnknownFails` | Fail-fast on misconfiguration. |
| `dispatchRunsOp` | The runnable submitted with `submit` actually executes. |
| `reservationGetsPriority` | A class with positive reservation is dispatched ahead of one with reservation=0. |
| `weightPhaseUsesSmallerTag` | When both classes have reservation=0, the one with larger weight (smaller w tag) is served first. |
| `limitPreventsOverServing` | Class A limit=1; over 5 dispatches, the scheduler refuses A once it hits the limit. |
| `dispatchEmptyReturnsNull` | Steady-state: no work → null. |
| `successfulOpCompletesNormally` | After dispatch, the submitted op's future is done. |
| `exceptionInOpCompletesExceptionally` | A throwing op's future is completed with the exception. |

## 6. Where it fits

**Upstream consumers:** `dfs-custodian` (dispatches work items through it); `dfs-simulator`.

**Downstream dependencies:** none beyond JDK.

**The dependency rule:** the scheduler has no opinion on what an op DOES. It runs `Runnable`s. The mapping from "this is critical repair" to "this goes through the `critical-repair` class" is in the Custodian. The scheduler just sees opaque class names.

## 7. Stubs and departures from production

- **Single-OSD scope.** Real dmClock runs per-OSD across the cluster; each OSD makes local scheduling decisions. This module is one in-process scheduler.
- **No external time source.** `virtualTime` advances by 1.0 per `dispatch` call. A real scheduler uses wall-clock time and adjusts for op durations.
- **No backpressure.** A real scheduler can refuse `submit` when queues are full. Here, `submit` always succeeds.
- **No per-shard interleaving.** Real OSDs interleave kernel-bypass I/O ops with scheduler ticks. Here, `dispatch` is purely a method call.
- **No elevation policy.** The wiki mentions auto-elevation of recovery class when a PG goes below durability floor. This module doesn't change class parameters at runtime; the simulator wires the policy externally if needed.
