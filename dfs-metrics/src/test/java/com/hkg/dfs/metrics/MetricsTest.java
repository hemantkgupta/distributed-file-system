package com.hkg.dfs.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsTest {

    @Test
    void counterIncrementsByOne() {
        Counter c = new Counter("dfs_put_ops_total");
        c.inc();
        c.inc();
        assertThat(c.get()).isEqualTo(2);
    }

    @Test
    void counterIncrementsByDelta() {
        Counter c = new Counter("dfs_bytes_written_total");
        c.inc(100);
        assertThat(c.get()).isEqualTo(100);
    }

    @Test
    void counterRejectsNegativeDelta() {
        Counter c = new Counter("x");
        assertThatThrownBy(() -> c.inc(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterRejectsBlankName() {
        assertThatThrownBy(() -> new Counter("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gaugeSetsAndGets() {
        Gauge g = new Gauge("dfs_osds_up");
        g.set(42);
        assertThat(g.get()).isEqualTo(42);
    }

    @Test
    void histogramRecordsAndCounts() {
        Histogram h = new Histogram("dfs_put_latency_seconds");
        h.record(0.005);
        h.record(0.5);
        h.record(20);
        assertThat(h.count()).isEqualTo(3);
    }

    @Test
    void histogramSumIsApproximate() {
        Histogram h = new Histogram("dfs_put_latency_seconds");
        h.record(1.0);
        h.record(2.0);
        assertThat(h.sum()).isCloseTo(3.0, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void exporterIncludesCounter() {
        var c = new Counter("dfs_x_total");
        c.inc();
        var out = new PrometheusExporter().register(c).expose();
        assertThat(out).contains("# TYPE dfs_x_total counter");
        assertThat(out).contains("dfs_x_total 1");
    }

    @Test
    void exporterIncludesGauge() {
        var g = new Gauge("dfs_y");
        g.set(7);
        var out = new PrometheusExporter().register(g).expose();
        assertThat(out).contains("# TYPE dfs_y gauge");
        assertThat(out).contains("dfs_y 7");
    }

    @Test
    void exporterIncludesHistogram() {
        var h = new Histogram("dfs_h");
        h.record(0.005);
        var out = new PrometheusExporter().register(h).expose();
        assertThat(out).contains("# TYPE dfs_h histogram");
        assertThat(out).contains("dfs_h_count 1");
    }

    @Test
    void exporterEmpty() {
        assertThat(new PrometheusExporter().expose()).isEmpty();
    }

    @Test
    void histogramHasFixedBuckets() {
        Histogram h = new Histogram("h");
        assertThat(h.bucketCount()).isEqualTo(Histogram.bounds().length + 1);
    }

    @Test
    void gaugeRejectsBlankName() {
        assertThatThrownBy(() -> new Gauge("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void histogramRejectsBlankName() {
        assertThatThrownBy(() -> new Histogram("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterNameAccessible() {
        assertThat(new Counter("dfs_z").name()).isEqualTo("dfs_z");
    }
}
