# dfs-metrics

> Last reconciled with the repo on 2026-05-20.

## 1. Role

The observability primitives that the rest of the repo would emit through if you wired them up. Three meter types — `Counter`, `Gauge`, `Histogram` — plus a `PrometheusExporter` that serializes them in Prometheus plaintext exposition format.

Naming convention: `dfs_<subsystem>_<metric>_<unit>{labels}`.

## 2. Wiki anchor

No single wiki concept page; the metrics catalog and naming convention are described in the design walkthrough at [`wiki/my-explanations/design-distributed-file-system`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/design-distributed-file-system.md) §Operational Concerns.

## 3. Public API surface

```java
package com.hkg.dfs.metrics;

public final class Counter {
    public Counter(String name);
    public void inc();
    public void inc(long n);
    public long get();
    public String name();
}

public final class Gauge {
    public Gauge(String name);
    public void set(long v);
    public long get();
    public String name();
}

public final class Histogram {
    public Histogram(String name);
    public void record(double v);
    public long bucket(int i);
    public long count();
    public double sum();
    public static double[] bounds();   // fixed bucket boundaries
    public int bucketCount();          // BOUNDS.length + 1
    public String name();
}

public final class PrometheusExporter {
    public PrometheusExporter register(Counter c);
    public PrometheusExporter register(Gauge g);
    public PrometheusExporter register(Histogram h);
    public String expose();    // Prometheus plaintext exposition
}
```

Source: `dfs-metrics/src/main/java/com/hkg/dfs/metrics/`.

## 4. Internal structure

- **`Counter`** — `AtomicLong`. Monotonic; `inc(-1)` is not allowed.
- **`Gauge`** — `AtomicLong` holding a settable long value (no double API).
- **`Histogram`** — fixed bucket boundaries `{0.001, 0.01, 0.1, 1, 10, 100, 1000, 10000}` plus a final overflow bucket, `AtomicLongArray` for per-bucket counters, separate `count` + `sum` atomics for mean computation. `sum` is stored in micro-units internally and divided by 1e6 on read.
- **`PrometheusExporter`** — three `ArrayList`s of meters; `expose()` walks them and produces the plaintext format with `# HELP`, `# TYPE`, and `_bucket{le="..."}` lines for histograms.

The expose output for a counter:

```
# HELP dfs_osd_writes_total counter
# TYPE dfs_osd_writes_total counter
dfs_osd_writes_total 42
```

For a histogram:

```
# HELP dfs_osd_write_latency_seconds histogram
# TYPE dfs_osd_write_latency_seconds histogram
dfs_osd_write_latency_seconds_bucket{le="0.001"} 5
dfs_osd_write_latency_seconds_bucket{le="0.01"} 17
dfs_osd_write_latency_seconds_bucket{le="+Inf"} 42
dfs_osd_write_latency_seconds_count 42
dfs_osd_write_latency_seconds_sum 0.85
```

## 5. Key tests

15 tests in `MetricsTest` (single test class covering all four meter types).

| Test | Demonstrates |
|---|---|
| `counterIncrementsByOne` / `counterIncrementsByDelta` | Both `inc()` forms accumulate correctly. |
| `counterRejectsNegativeDelta` | Monotonicity guarded at the API boundary. |
| `gaugeSetsAndGets` | `set(42)` then `get()` returns 42. |
| `histogramRecordsAndCounts` | Three records → `count() == 3`. |
| `histogramSumIsApproximate` | `sum()` returns the recorded total within 1e-3 (micro-unit storage). |
| `exporterIncludesCounter` / `exporterIncludesGauge` / `exporterIncludesHistogram` | Each meter type renders with `# TYPE` and value lines. |
| `exporterEmpty` | Exporter with no registrations produces empty output. |
| `histogramHasFixedBuckets` | `bucketCount() == bounds().length + 1` (one overflow bucket). |

## 6. Where it fits

**Upstream consumers:** none in the current repo. A production wiring would have every module instantiate its own counters/gauges/histograms and a single Exporter at boot.

**Downstream dependencies:** none beyond JDK.

**The dependency rule:** the metrics module knows nothing about the rest of the system. It's a generic meter library.

## 7. Stubs and departures from production

- **Not wired into other modules.** No module instantiates these meters today. A production version would add an instrumentation point at every interesting operation (write paths, lease grant, scheduler dispatch, etc.) and register them with one exporter.
- **No labels.** Production metrics are typically labeled (e.g. `dfs_osd_writes_total{osd_id="42", verdict="ok"}`). This module's exposition format includes the `{labels}` template but the API takes only the metric name.
- **Fixed histogram buckets.** Real histograms are configurable per-metric. Here, all histograms share the same bucket boundaries.
- **No OpenTelemetry export.** Production OJ uses OTel + OTLP. This module produces Prometheus plaintext only.
- **No HTTP server.** A real exporter binds an HTTP listener on `/metrics`. Here, `expose()` returns the string and the caller decides what to do with it.
