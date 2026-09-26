package com.myapp.benchmark;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

public class ResultWriter {
    public static final String PROTOCOL = "fairness-sweep-v2";
    private static final String CSV_HEADER = "test_id,run_number,database,engine,index,actual_recall,comparison_recall,average_ms,p50_ms,p95_ms,p99_ms,qps,measurement_time_ms,resource_samples,"
            + "filtered_queries,filtered_recall,filtered_average_ms,filtered_p50_ms,filtered_p95_ms,filtered_p99_ms,"
            + "filtered_scored_queries,filtered_empty_ground_truth_queries,filtered_empty_ground_truth_violations,"
            + "unfiltered_queries,unfiltered_recall,unfiltered_average_ms,unfiltered_p50_ms,unfiltered_p95_ms,unfiltered_p99_ms,"
            + "unfiltered_scored_queries,unfiltered_empty_ground_truth_queries,unfiltered_empty_ground_truth_violations,"
            + "cpu_average_percent,cpu_max_percent,ram_average_bytes,ram_max_bytes,disk_write_bytes,index_size_bytes,time_to_index_ready_ms,upsert_ms,vector_count,query_executions,concurrency,top_k,warmup_iterations,measurement_iterations,stability_verified,stability_diagnostics,index_parameters,search_parameters,environment,measured_at,filtered_strict_id_recall,unfiltered_strict_id_recall,audit\n";
    private final ObjectMapper objectMapper;

    public ResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void prepareDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            Path marker = directory.resolve("protocol.txt");
            Path csv = directory.resolve("csv/vector-db-result.csv");
            if (Files.exists(csv) && !Files.readString(csv).startsWith(CSV_HEADER)) {
                throw new IllegalStateException("Existing CSV uses the old protocol; choose a new result directory: " + directory);
            }
            if (Files.exists(marker)) {
                if (!Files.readString(marker).trim().equals(PROTOCOL)) {
                    throw new IllegalStateException("Incompatible benchmark protocol: " + directory);
                }
            } else {
                Path raw = directory.resolve("raw");
                if (Files.isDirectory(raw)) {
                    try (var paths = Files.list(raw)) {
                        if (paths.anyMatch(path -> path.getFileName().toString().startsWith("benchmark-"))) {
                            throw new IllegalStateException("Unversioned historical results must stay in a separate directory: " + directory);
                        }
                    }
                }
                Files.writeString(marker, PROTOCOL + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot prepare result directory", exception);
        }
    }

    public synchronized Artifacts write(List<BenchmarkResult> results, Path resultDirectory) {
        if (results.isEmpty()) throw new IllegalArgumentException("results must not be empty");
        prepareDirectory(resultDirectory);
        Path rawDirectory = resultDirectory.resolve("raw");
        Path csvDirectory = resultDirectory.resolve("csv");
        Path chartDirectory = resultDirectory.resolve("charts");
        try {
            Files.createDirectories(rawDirectory);
            Files.createDirectories(csvDirectory);
            Files.createDirectories(chartDirectory);
            String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
                    .format(results.getFirst().measuredAt()) + "-" + UUID.randomUUID();
            Path json = rawDirectory.resolve("benchmark-" + runId + ".json");
            writeAtomic(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));

            Path csv = csvDirectory.resolve("vector-db-result.csv");
            List<BenchmarkResult> allResults = readAllResults(rawDirectory);
            StringBuilder rows = new StringBuilder(CSV_HEADER);
            allResults.forEach(result -> rows.append(toCsv(result)));
            writeAtomic(csv, rows.toString());
            Path chart = chartDirectory.resolve("recall-latency-latest.svg");
            writeAtomic(chart, RecallLatencyPlot.svg(allResults, objectMapper, PROTOCOL));
            Path summaryDirectory = resultDirectory.resolve("summary");
            Files.createDirectories(summaryDirectory);
            List<BenchmarkSummary.Group> summary = BenchmarkSummary.aggregate(allResults);
            Path summaryJson = summaryDirectory.resolve("vector-db-summary.json");
            Path summaryCsv = summaryDirectory.resolve("vector-db-summary.csv");
            writeAtomic(summaryJson, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            writeAtomic(summaryCsv, summaryCsv(summary));
            return new Artifacts(json, csv, chart, summaryJson, summaryCsv);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write benchmark results", exception);
        }
    }

    public synchronized void writeFailure(BenchmarkScenario scenario, RuntimeException failure, Path directory) {
        try {
            Path failures = directory.resolve("failures");
            Files.createDirectories(failures);
            writeAtomic(failures.resolve("failure-" + UUID.randomUUID() + ".json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "scenario", scenario, "error", failure.toString(), "measuredAt", java.time.Instant.now(),
                            "status", "execution_error", "completedMeasurementsPreserved", true)));
        } catch (IOException exception) {
            failure.addSuppressed(exception);
        }
    }

    private void writeAtomic(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".report-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String summaryCsv(List<BenchmarkSummary.Group> groups) {
        List<String> metricNames = List.copyOf(groups.getFirst().metrics().keySet());
        StringBuilder output = new StringBuilder("database,engine,index,index_parameters,search_parameters,vector_count,top_k,concurrency,warmup_iterations,measurement_iterations,environment,completed_measurements,run_numbers");
        for (String metric : metricNames) {
            for (String statistic : List.of("samples", "mean", "median", "p95", "p99", "min", "max", "sample_variance")) {
                output.append(',').append(metric).append('_').append(statistic);
            }
        }
        output.append('\n');
        for (BenchmarkSummary.Group group : groups) {
            var c = group.configuration();
            List<String> cells = new ArrayList<>(List.of(csv(c.database()), csv(c.engine()), csv(c.indexType()),
                    csv(objectMapper.writeValueAsString(c.indexParameters())), csv(objectMapper.writeValueAsString(c.searchParameters())),
                    "" + c.vectorCount(), "" + c.topK(), "" + c.concurrency(), "" + c.warmupIterations(),
                    "" + c.measurementIterations(), csv(objectMapper.writeValueAsString(c.environment())),
                    "" + group.completedMeasurements(), csv(objectMapper.writeValueAsString(group.runNumbers()))));
            for (String metric : metricNames) {
                var d = group.metrics().get(metric);
                cells.add("" + d.samples());
                cells.add(csvNumber(d.mean()));
                cells.add(csvNumber(d.median()));
                cells.add(csvNumber(d.p95()));
                cells.add(csvNumber(d.p99()));
                cells.add(csvNumber(d.min()));
                cells.add(csvNumber(d.max()));
                cells.add(csvNumber(d.sampleVariance()));
            }
            output.append(String.join(",", cells)).append('\n');
        }
        return output.toString();
    }

    private List<BenchmarkResult> readAllResults(Path rawDirectory) throws IOException {
        List<Path> files;
        try (var paths = Files.list(rawDirectory)) {
            files = paths.filter(path -> path.getFileName().toString().startsWith("benchmark-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        List<BenchmarkResult> results = new ArrayList<>();
        for (Path file : files) {
            BenchmarkResult[] run = objectMapper.readValue(file.toFile(), BenchmarkResult[].class);
            results.addAll(List.of(run));
        }
        return List.copyOf(results);
    }

    public synchronized Path writeGroundTruth(
            Map<String, List<com.myapp.domain.vector.VectorSearchResult>> groundTruth,
            int topK,
            Path resultDirectory
    ) {
        Path rawDirectory = resultDirectory.resolve("raw");
        Path target = rawDirectory.resolve("ground-truth-top" + topK + ".jsonl");
        try {
            Files.createDirectories(rawDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<com.myapp.domain.vector.VectorSearchResult>> entry : groundTruth.entrySet()) {
                    writer.write(objectMapper.writeValueAsString(Map.of(
                            "queryId", entry.getKey(),
                            "topK", entry.getValue().stream().limit(topK).map(com.myapp.domain.vector.VectorSearchResult::id).toList())));
                    writer.newLine();
                }
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write ground truth", exception);
        }
    }

    private String toCsv(BenchmarkResult result) {
        List<String> cells = new ArrayList<>();
        cells.add(csv(result.testId()));
        cells.add(Integer.toString(result.runNumber()));
        cells.add(csv(result.database()));
        cells.add(csv(result.engine()));
        cells.add(csv(result.indexType()));
        cells.add(decimal(result.actualRecall(), 6));
        cells.add(decimal(result.comparisonRecall(), 6));
        cells.add(decimal(result.averageLatencyMs(), 6));
        cells.add(decimal(result.p50LatencyMs(), 6));
        cells.add(decimal(result.p95LatencyMs(), 6));
        cells.add(decimal(result.p99LatencyMs(), 6));
        cells.add(decimal(result.qps(), 3));
        cells.add(Long.toString(result.measurementTimeMs()));
        cells.add(Integer.toString(result.resourceSamples()));
        addSegment(cells, result.filtered());
        addSegment(cells, result.unfiltered());
        cells.add(decimal(result.averageCpuPercent(), 3));
        cells.add(decimal(result.peakCpuPercent(), 3));
        cells.add(Long.toString(result.averageMemoryBytes()));
        cells.add(Long.toString(result.peakMemoryBytes()));
        cells.add(Long.toString(result.diskWriteBytes()));
        cells.add(Long.toString(result.indexSizeBytes()));
        cells.add(Long.toString(result.indexBuildTimeMs()));
        cells.add(Long.toString(result.upsertTimeMs()));
        cells.add(Integer.toString(result.vectorCount()));
        cells.add(Integer.toString(result.queryExecutions()));
        cells.add(Integer.toString(result.concurrency()));
        cells.add(Integer.toString(result.topK()));
        cells.add(Integer.toString(result.warmupIterations()));
        cells.add(Integer.toString(result.measurementIterations()));
        cells.add(Boolean.toString(result.stabilityDiagnostics().verified()));
        cells.add(csv(objectMapper.writeValueAsString(result.stabilityDiagnostics())));
        cells.add(csv(objectMapper.writeValueAsString(result.indexParameters())));
        cells.add(csv(objectMapper.writeValueAsString(result.searchParameters())));
        cells.add(csv(objectMapper.writeValueAsString(result.environment())));
        cells.add(result.measuredAt().toString());
        cells.add(csvNumber(result.filtered().strictIdRecall()));
        cells.add(csvNumber(result.unfiltered().strictIdRecall()));
        cells.add(csv(objectMapper.writeValueAsString(result.audit())));
        return String.join(",", cells) + System.lineSeparator();
    }

    private void addSegment(List<String> cells, QuerySegment segment) {
        cells.add(Integer.toString(segment.queryExecutions()));
        cells.add(csvNumber(segment.recall()));
        cells.add(decimal(segment.averageMs(), 6));
        cells.add(decimal(segment.p50Ms(), 6));
        cells.add(decimal(segment.p95Ms(), 6));
        cells.add(decimal(segment.p99Ms(), 6));
        cells.add(Integer.toString(segment.scoredQueries()));
        cells.add(Integer.toString(segment.emptyGroundTruthQueries()));
        cells.add(Integer.toString(segment.emptyGroundTruthViolations()));
    }

    public synchronized void writeTieGroundTruth(Map<String, ExactGroundTruth> truths, int topK, Path directory) {
        try {
            Path raw = directory.resolve("raw");
            Files.createDirectories(raw);
            StringBuilder lines = new StringBuilder();
            for (var entry : truths.entrySet()) {
                lines.append(objectMapper.writeValueAsString(Map.of("queryId", entry.getKey(),
                        "topK", topK, "truth", entry.getValue()))).append('\n');
            }
            writeAtomic(raw.resolve("ground-truth-top" + topK + ".jsonl"), lines.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist tie-aware ground truth", exception);
        }
    }

    /** Lossless aggregation of identical repeated responses, after timing ends. */
    public synchronized String writeQueryAudit(Map<BenchmarkRunner.QueryAuditKey, Integer> outcomes, Path directory) {
        String filename = "raw/query-audit-" + UUID.randomUUID() + ".jsonl.gz";
        try {
            Files.createDirectories(directory.resolve("raw"));
            try (var writer = new BufferedWriter(new java.io.OutputStreamWriter(
                    new java.util.zip.GZIPOutputStream(Files.newOutputStream(directory.resolve(filename),
                            StandardOpenOption.CREATE_NEW)), StandardCharsets.UTF_8))) {
                for (var entry : outcomes.entrySet()) {
                    writer.write(objectMapper.writeValueAsString(Map.of("outcome", entry.getKey(), "executions", entry.getValue())));
                    writer.newLine();
                }
            }
            return filename;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist query audit", exception);
        }
    }

    private String decimal(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    private String csvNumber(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public record Artifacts(Path json, Path csv, Path chart, Path summaryJson, Path summaryCsv) {
    }
}
