package com.myapp.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Records search latency separately for filtered and unfiltered queries.
 * A mixed query set puts the whole filtered population into the tail percentiles of the
 * combined distribution, so p95/p99 of the combined series describes filter cost rather
 * than ANN tail behaviour. Both series are kept so the two can be reported apart.
 */
public class LatencyCollector {
    private final ConcurrentLinkedQueue<Long> filtered = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> unfiltered = new ConcurrentLinkedQueue<>();

    public void record(long elapsedNanoseconds) {
        record(elapsedNanoseconds, false);
    }

    public void record(long elapsedNanoseconds, boolean filteredQuery) {
        if (elapsedNanoseconds < 0) throw new IllegalArgumentException("elapsedNanoseconds must not be negative");
        (filteredQuery ? filtered : unfiltered).add(elapsedNanoseconds);
    }

    public Statistics statistics() {
        List<Long> combined = new ArrayList<>(filtered);
        combined.addAll(unfiltered);
        return statistics(combined);
    }

    public Statistics filteredStatistics() {
        return statistics(new ArrayList<>(filtered));
    }

    public Statistics unfilteredStatistics() {
        return statistics(new ArrayList<>(unfiltered));
    }

    private Statistics statistics(List<Long> values) {
        if (values.isEmpty()) return Statistics.EMPTY;
        values.sort(Long::compareTo);
        double average = values.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        return new Statistics(values.size(), average,
                percentile(values, 0.50), percentile(values, 0.95), percentile(values, 0.99));
    }

    private double percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    public record Statistics(int count, double averageMs, double p50Ms, double p95Ms, double p99Ms) {
        public static final Statistics EMPTY = new Statistics(0, 0, 0, 0, 0);
    }
}
