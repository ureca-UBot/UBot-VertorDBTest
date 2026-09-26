package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyCollectorTest {
    @Test
    void calculatesNearestRankPercentilesInMilliseconds() {
        LatencyCollector collector = new LatencyCollector();
        for (int millis = 1; millis <= 100; millis++) collector.record(millis * 1_000_000L);

        LatencyCollector.Statistics stats = collector.statistics();

        assertThat(stats.averageMs()).isEqualTo(50.5);
        assertThat(stats.p50Ms()).isEqualTo(50);
        assertThat(stats.p95Ms()).isEqualTo(95);
        assertThat(stats.p99Ms()).isEqualTo(99);
        assertThat(stats.count()).isEqualTo(100);
    }

    @Test
    void keepsFilteredAndUnfilteredSeriesApart() {
        LatencyCollector collector = new LatencyCollector();
        for (int millis = 1; millis <= 90; millis++) collector.record(millis * 1_000_000L, false);
        for (int millis = 500; millis < 510; millis++) collector.record(millis * 1_000_000L, true);

        // A tenth of the queries carrying a filter puts that whole population past p90 of the
        // combined series, which is exactly what makes the combined p95 unusable as an ANN metric.
        assertThat(collector.statistics().p95Ms()).isEqualTo(504);
        assertThat(collector.unfilteredStatistics().p95Ms()).isEqualTo(86);
        assertThat(collector.unfilteredStatistics().count()).isEqualTo(90);
        assertThat(collector.filteredStatistics().p50Ms()).isEqualTo(504);
        assertThat(collector.filteredStatistics().count()).isEqualTo(10);
    }

    @Test
    void reportsEmptyStatisticsForAnAbsentSegment() {
        LatencyCollector collector = new LatencyCollector();
        collector.record(5_000_000L, false);

        assertThat(collector.filteredStatistics()).isEqualTo(LatencyCollector.Statistics.EMPTY);
        assertThat(collector.unfilteredStatistics().count()).isEqualTo(1);
    }
}
