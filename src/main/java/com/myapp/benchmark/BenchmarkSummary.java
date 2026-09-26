package com.myapp.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Repeated observations of the same fixed configuration, without quality eligibility rules. */
public final class BenchmarkSummary {
    private BenchmarkSummary() { }

    public static List<Group> aggregate(List<BenchmarkResult> results) {
        Map<Configuration, List<BenchmarkResult>> groups = new LinkedHashMap<>();
        for (BenchmarkResult result : results) {
            groups.computeIfAbsent(Configuration.of(result), ignored -> new ArrayList<>()).add(result);
        }
        return groups.entrySet().stream().map(entry -> {
            List<BenchmarkResult> samples = entry.getValue();
            Map<String, Distribution> metrics = new LinkedHashMap<>();
            add(metrics, "recall", samples, BenchmarkResult::actualRecall);
            add(metrics, "unfiltered_recall", samples, r -> r.unfiltered().recall() == null ? -1 : r.unfiltered().recall());
            add(metrics, "filtered_recall", samples, r -> r.filtered().recall() == null ? -1 : r.filtered().recall());
            add(metrics, "unfiltered_strict_id_recall", samples, r -> r.unfiltered().strictIdRecall() == null ? -1 : r.unfiltered().strictIdRecall());
            add(metrics, "filtered_strict_id_recall", samples, r -> r.filtered().strictIdRecall() == null ? -1 : r.filtered().strictIdRecall());
            add(metrics, "search_failures", samples, r -> r.audit().searchFailures());
            add(metrics, "invalid_response_queries", samples, r -> r.audit().invalidResponseQueries());
            add(metrics, "no_hit_violations", samples, r -> r.filtered().emptyGroundTruthViolations() + r.unfiltered().emptyGroundTruthViolations());
            add(metrics, "average_ms", samples, BenchmarkResult::averageLatencyMs);
            add(metrics, "p50_ms", samples, BenchmarkResult::p50LatencyMs);
            add(metrics, "p95_ms", samples, BenchmarkResult::p95LatencyMs);
            add(metrics, "p99_ms", samples, BenchmarkResult::p99LatencyMs);
            add(metrics, "qps", samples, BenchmarkResult::qps);
            add(metrics, "measurement_time_ms", samples, BenchmarkResult::measurementTimeMs);
            add(metrics, "resource_samples", samples, BenchmarkResult::resourceSamples);
            add(metrics, "cpu_average_percent", samples, BenchmarkResult::averageCpuPercent);
            add(metrics, "cpu_max_percent", samples, BenchmarkResult::peakCpuPercent);
            add(metrics, "ram_average_bytes", samples, BenchmarkResult::averageMemoryBytes);
            add(metrics, "ram_max_bytes", samples, BenchmarkResult::peakMemoryBytes);
            add(metrics, "index_size_bytes", samples, BenchmarkResult::indexSizeBytes);
            add(metrics, "time_to_index_ready_ms", samples, BenchmarkResult::indexBuildTimeMs);
            return new Group(entry.getKey(), samples.size(),
                    samples.stream().map(BenchmarkResult::runNumber).toList(),
                    samples.stream().map(result -> result.measuredAt().toString()).toList(), metrics, DecisionGate.evaluate(samples));
        }).toList();
    }

    private static void add(Map<String, Distribution> metrics, String name, List<BenchmarkResult> samples,
                            ToDoubleFunction<BenchmarkResult> metric) {
        metrics.put(name, Distribution.of(samples.stream().mapToDouble(metric).boxed().toList()));
    }

    public record Configuration(String database, String engine, String indexType,
                                Map<String, Object> indexParameters, Map<String, Object> searchParameters,
                                int vectorCount, int topK, int concurrency, int warmupIterations,
                                int measurementIterations, Map<String, Object> environment) {
        static Configuration of(BenchmarkResult result) {
            Map<String, Object> context = new LinkedHashMap<>(result.environment());
            // Snapshot row counts/timings vary per invocation; the input hashes already identify the data.
            context.remove("sourceOfTruth");
            return new Configuration(result.database(), result.engine(), result.indexType(), result.indexParameters(),
                    result.searchParameters(), result.vectorCount(), result.topK(), result.concurrency(),
                    result.warmupIterations(), result.measurementIterations(), Map.copyOf(context));
        }
    }

    public record Group(Configuration configuration, int completedMeasurements, List<Integer> runNumbers,
                        List<String> measuredAt, Map<String, Distribution> metrics, DecisionGate.Verdict decision) { }

    /** Percentiles describe the distribution ACROSS repetitions, not pooled request latencies. */
    public record Distribution(int samples, Double mean, Double median, Double p95, Double p99,
                               Double min, Double max, Double sampleVariance) {
        static Distribution of(List<Double> values) {
            // -1 means resource telemetry was unavailable; it must never look like cheap resource use.
            List<Double> sorted = values.stream().filter(value -> Double.isFinite(value) && value >= 0).sorted().toList();
            int count = sorted.size();
            if (count == 0) return new Distribution(0, null, null, null, null, null, null, null);
            double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double median = count % 2 == 1 ? sorted.get(count / 2)
                    : (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2;
            Double variance = count < 2 ? null
                    : sorted.stream().mapToDouble(value -> (value - mean) * (value - mean)).sum() / (count - 1);
            return new Distribution(count, mean, median, sorted.get((int) Math.ceil(count * .95) - 1),
                    sorted.get((int) Math.ceil(count * .99) - 1), sorted.getFirst(), sorted.getLast(), variance);
        }
    }
}
