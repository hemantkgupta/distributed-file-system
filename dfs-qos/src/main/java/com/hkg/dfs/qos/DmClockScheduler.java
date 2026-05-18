package com.hkg.dfs.qos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * dmClock scheduler. Each class carries three virtual-time tags
 * (reservation, weight, limit). The scheduler runs in two phases:
 * <ol>
 *   <li>Reservation phase: dispatch tasks whose reservation tag is the
 *   smallest and below the current real time — these are guaranteed
 *   minimum capacity.</li>
 *   <li>Weight phase: among classes that are not above their limit tag,
 *   dispatch the one with the smallest weight tag.</li>
 * </ol>
 * Implements {@code wiki/concepts/dmclock-qos}.
 */
public final class DmClockScheduler {
    private final Map<String, QosClass> classes = new LinkedHashMap<>();
    private final Map<String, Deque<Op>> queues = new HashMap<>();
    private final Map<String, Tags> tags = new HashMap<>();
    private final Map<String, Long> served = new HashMap<>();
    private double virtualTime = 0;

    public synchronized void addClass(QosClass cls) {
        if (classes.containsKey(cls.name())) {
            throw new IllegalStateException("class exists: " + cls.name());
        }
        classes.put(cls.name(), cls);
        queues.put(cls.name(), new ArrayDeque<>());
        tags.put(cls.name(), new Tags(0, 0, 0));
        served.put(cls.name(), 0L);
    }

    public synchronized CompletableFuture<Void> submit(String className, Runnable op) {
        QosClass c = classes.get(className);
        if (c == null) throw new IllegalStateException("no class: " + className);
        CompletableFuture<Void> done = new CompletableFuture<>();
        queues.get(className).add(new Op(op, done));
        Tags t = tags.get(className);
        double now = virtualTime;
        tags.put(className, new Tags(
                Math.max(t.r, now) + (c.reservation() > 0 ? 1.0 / c.reservation() : Double.POSITIVE_INFINITY),
                Math.max(t.w, now) + 1.0 / c.weight(),
                Math.max(t.l, now) + 1.0 / c.limit()
        ));
        return done;
    }

    /**
     * Run one scheduling step. Returns the name of the class served, or
     * null when nothing was available.
     */
    public synchronized String dispatch() {
        virtualTime += 1.0;
        // Reservation phase: choose smallest R tag with non-empty queue and R <= now
        String chosen = null;
        double bestR = Double.POSITIVE_INFINITY;
        for (var e : classes.entrySet()) {
            if (queues.get(e.getKey()).isEmpty()) continue;
            Tags t = tags.get(e.getKey());
            if (t.r <= virtualTime && t.r < bestR) {
                bestR = t.r;
                chosen = e.getKey();
            }
        }
        if (chosen == null) {
            double bestW = Double.POSITIVE_INFINITY;
            for (var e : classes.entrySet()) {
                if (queues.get(e.getKey()).isEmpty()) continue;
                Tags t = tags.get(e.getKey());
                if (t.l > virtualTime) continue;
                if (t.w < bestW) {
                    bestW = t.w;
                    chosen = e.getKey();
                }
            }
        }
        if (chosen == null) return null;
        Op op = queues.get(chosen).poll();
        served.merge(chosen, 1L, Long::sum);
        try {
            op.run.run();
            op.done.complete(null);
        } catch (RuntimeException x) {
            op.done.completeExceptionally(x);
        }
        return chosen;
    }

    public synchronized long served(String className) {
        return served.getOrDefault(className, 0L);
    }

    public synchronized int queueDepth(String className) {
        var q = queues.get(className);
        return q == null ? 0 : q.size();
    }

    private record Tags(double r, double w, double l) {}

    private record Op(Runnable run, CompletableFuture<Void> done) {}
}
