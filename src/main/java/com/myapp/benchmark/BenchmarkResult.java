package com.myapp.benchmark;

import java.time.Instant;
import java.util.Map;

public record BenchmarkResult(
        String testId,
        int runNumber,
        String database,
        String engine,
        String indexType,
        double actualRecall,
        Double comparisonRecall,
        double averageLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double qps,
        long measurementTimeMs,
        int resourceSamples,
        QuerySegment filtered,
        QuerySegment unfiltered,
        double averageCpuPercent,
        double peakCpuPercent,
        long averageMemoryBytes,
        long peakMemoryBytes,
        long diskWriteBytes,
        long indexSizeBytes,
        long indexBuildTimeMs,
        long upsertTimeMs,
        int vectorCount,
        int queryExecutions,
        int concurrency,
        int topK,
        int warmupIterations,
        int measurementIterations,
        StabilityDiagnostics stabilityDiagnostics,
        Map<String, Object> indexParameters,
        Map<String, Object> searchParameters,
        Map<String, Object> environment,
        Instant measuredAt,
        MeasurementAudit audit
) {
    public BenchmarkResult {
        // Result files written before the segment split deserialize these as null.
        filtered = filtered == null ? QuerySegment.EMPTY : filtered;
        unfiltered = unfiltered == null ? QuerySegment.EMPTY : unfiltered;
        comparisonRecall = comparisonRecall == null
                ? (unfiltered.recall() == null ? actualRecall : unfiltered.recall())
                : comparisonRecall;
        if (!Double.isFinite(comparisonRecall)) {
            throw new IllegalArgumentException("comparisonRecall must be finite");
        }
        stabilityDiagnostics = stabilityDiagnostics == null
                ? StabilityDiagnostics.notRequired()
                : stabilityDiagnostics;
        indexParameters = indexParameters == null ? Map.of() : Map.copyOf(indexParameters);
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        audit = audit == null ? MeasurementAudit.unavailable() : audit;
    }
}
