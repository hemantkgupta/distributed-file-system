package com.hkg.dfs.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes registered metrics in Prometheus plaintext format. Names follow
 * {@code dfs_<subsystem>_<metric>_<unit>{labels}}.
 */
public final class PrometheusExporter {
    private final List<Counter> counters = new ArrayList<>();
    private final List<Gauge> gauges = new ArrayList<>();
    private final List<Histogram> histograms = new ArrayList<>();

    public PrometheusExporter register(Counter c) { counters.add(c); return this; }
    public PrometheusExporter register(Gauge g) { gauges.add(g); return this; }
    public PrometheusExporter register(Histogram h) { histograms.add(h); return this; }

    public String expose() {
        StringBuilder sb = new StringBuilder();
        for (Counter c : counters) {
            sb.append("# HELP ").append(c.name()).append(" counter\n");
            sb.append("# TYPE ").append(c.name()).append(" counter\n");
            sb.append(c.name()).append(' ').append(c.get()).append('\n');
        }
        for (Gauge g : gauges) {
            sb.append("# HELP ").append(g.name()).append(" gauge\n");
            sb.append("# TYPE ").append(g.name()).append(" gauge\n");
            sb.append(g.name()).append(' ').append(g.get()).append('\n');
        }
        for (Histogram h : histograms) {
            sb.append("# HELP ").append(h.name()).append(" histogram\n");
            sb.append("# TYPE ").append(h.name()).append(" histogram\n");
            double[] bounds = Histogram.bounds();
            long cum = 0;
            for (int i = 0; i < bounds.length; i++) {
                cum += h.bucket(i);
                sb.append(h.name()).append("_bucket{le=\"").append(bounds[i]).append("\"} ").append(cum).append('\n');
            }
            cum += h.bucket(bounds.length);
            sb.append(h.name()).append("_bucket{le=\"+Inf\"} ").append(cum).append('\n');
            sb.append(h.name()).append("_count ").append(h.count()).append('\n');
            sb.append(h.name()).append("_sum ").append(h.sum()).append('\n');
        }
        return sb.toString();
    }
}
