package com.myapp.benchmark;

import java.util.List;

/**
 * Latency and recall for one slice of the query set. Filtered and unfiltered queries are
 * reported apart because they exercise different code paths in every product.
 *
 * <p>{@code recall} averages only the searches whose query has a non-empty exact top-K
 * ({@code scoredQueries}). Searches for a query whose filter matches no document are counted in
 * {@code emptyGroundTruthQueries} and scored by whether the store correctly returned nothing;
 * {@code emptyGroundTruthViolations} counts those that returned rows anyway. Folding them into the
 * average as a free 1.0 both inflates recall and hides the filter defect.
 *
 * <p>{@code recall} is null when the slice has no scored query.
 */
public record QuerySegment(
        int queryExecutions,
        Double recall,
        double averageMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        int scoredQueries,
        int emptyGroundTruthQueries,
        int emptyGroundTruthViolations,
        Double strictIdRecall
) {
    public QuerySegment(int queryExecutions, Double recall, double averageMs, double p50Ms, double p95Ms,
                        double p99Ms, int scoredQueries, int emptyGroundTruthQueries, int emptyGroundTruthViolations) {
        this(queryExecutions, recall, averageMs, p50Ms, p95Ms, p99Ms, scoredQueries,
                emptyGroundTruthQueries, emptyGroundTruthViolations, recall);
    }
    public static final QuerySegment EMPTY = new QuerySegment(0, null, 0, 0, 0, 0, 0, 0, 0);

    static QuerySegment of(LatencyCollector.Statistics latency, Accumulator accumulator) {
        if (latency.count() == 0) return EMPTY;
        List<Double> recalls = accumulator.recalls();
        Double recall = recalls.isEmpty()
                ? null
                : recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new QuerySegment(latency.count(), recall,
                latency.averageMs(), latency.p50Ms(), latency.p95Ms(), latency.p99Ms(),
                recalls.size(), accumulator.emptyGroundTruthQueries(), accumulator.emptyGroundTruthViolations(),
                accumulator.strictRecalls.isEmpty() ? null : accumulator.strictRecalls.stream()
                        .mapToDouble(Double::doubleValue).average().orElseThrow());
    }

    /** Collects one slice's per-search outcomes while the recall post-processing loop runs. */
    static final class Accumulator {
        private final List<Double> recalls = new java.util.ArrayList<>();
        private final List<Double> strictRecalls = new java.util.ArrayList<>();
        private int emptyGroundTruthQueries;
        private int emptyGroundTruthViolations;

        void recordScored(double recall) {
            recordScored(recall, recall);
        }

        void recordScored(double recall, double strictRecall) {
            recalls.add(recall);
            strictRecalls.add(strictRecall);
        }

        void recordEmptyGroundTruth(boolean returnedNothing) {
            emptyGroundTruthQueries++;
            if (!returnedNothing) emptyGroundTruthViolations++;
        }

        List<Double> recalls() {
            return recalls;
        }

        int emptyGroundTruthQueries() {
            return emptyGroundTruthQueries;
        }

        int emptyGroundTruthViolations() {
            return emptyGroundTruthViolations;
        }
    }
}
