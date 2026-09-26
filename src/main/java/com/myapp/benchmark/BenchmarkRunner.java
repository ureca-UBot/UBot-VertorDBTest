package com.myapp.benchmark;

import com.myapp.dataset.QuerySetLoader;
import com.myapp.dataset.VectorDatasetLoader;
import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.rdb.postgres.BenchmarkSourceOfTruthSynchronizer;
import com.myapp.port.VectorIndexManager;
import com.myapp.port.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class BenchmarkRunner {
    private final ObjectProvider<VectorStore> storeProvider;
    private final ObjectProvider<VectorIndexManager> indexManagerProvider;
    private final ObjectProvider<BenchmarkSourceOfTruthSynchronizer> sourceOfTruthProvider;
    private final BenchmarkProperties properties;
    private final VectorDatasetLoader vectorDatasetLoader;
    private final QuerySetLoader querySetLoader;
    private final RecallCalculator recallCalculator = new RecallCalculator();
    private final ResourceCollector resourceCollector = new ResourceCollector();
    private final BenchmarkEnvironmentCollector environmentCollector = new BenchmarkEnvironmentCollector();
    private final ResultWriter resultWriter;
    private final HoldoutGuard holdoutGuard;

    public BenchmarkRunner(
            ObjectProvider<VectorStore> storeProvider,
            ObjectProvider<VectorIndexManager> indexManagerProvider,
            ObjectProvider<BenchmarkSourceOfTruthSynchronizer> sourceOfTruthProvider,
            BenchmarkProperties properties,
            ObjectMapper objectMapper
    ) {
        this.storeProvider = storeProvider;
        this.indexManagerProvider = indexManagerProvider;
        this.sourceOfTruthProvider = sourceOfTruthProvider;
        this.properties = properties;
        this.vectorDatasetLoader = new VectorDatasetLoader(objectMapper);
        this.querySetLoader = new QuerySetLoader(objectMapper);
        this.resultWriter = new ResultWriter(objectMapper);
        this.holdoutGuard = new HoldoutGuard(objectMapper);
    }

    public synchronized RunOutput run(List<BenchmarkScenario> scenarios, boolean rebuildAndLoad) {
        if (scenarios == null || scenarios.isEmpty()) throw new IllegalArgumentException("At least one scenario is required");
        VectorStore store = requiredStore();
        VectorIndexManager indexManager = requiredIndexManager();
        validateScenarios(scenarios, store, indexManager);
        // Validate the entire grid before rebuilding an index or starting any measurements.
        List<List<Map<String, Object>>> grids = scenarios.stream()
                .map(scenario -> SearchParameterSweep.parameters(scenario, indexManager, properties.getSearchParameterValues()))
                .toList();
        resultWriter.prepareDirectory(properties.getResultDirectory());

        List<VectorDocument> documents = vectorDatasetLoader.load(properties.getDocumentVectors());
        List<BenchmarkQuery> queries = querySetLoader.load(properties.getQueryDefinitions(), properties.getQueryVectors());
        Map<String, Object> validation = holdoutGuard.validate(properties, scenarios, queries, indexManager.indexParameters());
        QueryPartitioner.Partition queryPartition = "holdout".equals(properties.getMode())
                ? new QueryPartitioner.Partition(holdoutGuard.calibrationQueries(properties), queries, "prior-inputs-in-plan",
                        BenchmarkEnvironmentCollector.sha256(properties.getQueryVectors()))
                : QueryPartitioner.split(queries, properties.getCalibrationQueryCount());
        validateDimensions(documents, queries, store);
        ResponseContract contract = new ResponseContract(documents);
        List<BenchmarkQuery> truthQueries = new ArrayList<>(queryPartition.calibration());
        truthQueries.addAll(queryPartition.evaluation());
        validateDimensions(documents, truthQueries, store);
        com.myapp.domain.vector.MetadataContract.validate(documents, truthQueries);
        Map<String, Object> environmentValues = new LinkedHashMap<>(environmentCollector.collect(properties));
        environmentValues.put("protocol", ResultWriter.PROTOCOL);
        environmentValues.put("mode", properties.getMode());
        environmentValues.put("validation", validation);
        environmentValues.put("tieTolerance", properties.getTieTolerance());
        environmentValues.put("recallDefinition", "capped-boundary-ties-from-source-vectors; strict-ID retained");
        environmentValues.put("searchOrder", "deterministic-shuffle-by-run-number");
        environmentValues.put("maximumMeasurementTimeMs", properties.getMaximumMeasurementTimeMs());
        environmentValues.put("minimumResourceSamples", properties.getMinimumResourceSamples());
        environmentValues.put("warmupMaximumPasses", properties.getWarmupMaximumPasses());
        environmentValues.put("warmupRelativeTolerance", properties.getWarmupRelativeTolerance());
        environmentValues.put("referenceRecallLevels", List.of(0.90, 0.95));
        environmentValues.put("minimumMeasurementTimeMs", properties.getMinimumMeasurementTimeMs());
        environmentValues.put("resourceSampling", "capture-window-contained-in-search-window");
        environmentValues.put("queryPartition", Map.of(
                "method", "holdout".equals(properties.getMode()) ? "frozen-plan-prior-vs-unseen" : "query-type-stratified-sha256",
                "calibrationQueries", queryPartition.calibration().size(),
                "evaluationQueries", queryPartition.evaluation().size(),
                "calibrationIdsSha256", queryPartition.calibrationIdsSha256(),
                "evaluationIdsSha256", queryPartition.evaluationIdsSha256()));
        sourceOfTruthProvider.ifAvailable(synchronizer -> environmentValues.put("sourceOfTruth",
                synchronizer.synchronize(documents,
                        environmentValues.get("documentVectorsSha256").toString(), rebuildAndLoad)));
        Map<String, Object> environment = Map.copyOf(environmentValues);

        ExactSearchEngine exactSearch = new ExactSearchEngine(documents, properties.getMetric());
        Map<Integer, Map<String, ExactGroundTruth>> groundTruthByTopK = new LinkedHashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();
        ResultWriter.Artifacts artifacts = null;
        for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
            BenchmarkScenario scenario = scenarios.get(scenarioIndex);
            Map<String, ExactGroundTruth> groundTruth = groundTruthByTopK.computeIfAbsent(
                    scenario.topK(), ignored -> exactGroundTruth(exactSearch, truthQueries, scenario));
            resultWriter.writeTieGroundTruth(groundTruth, scenario.topK(), properties.getResultDirectory());
            for (int repetition = 0; repetition < scenario.repetitions(); repetition++) {
                String buildId = UUID.randomUUID().toString();
                long indexBuildTimeMs = -1;
                long upsertTimeMs = -1;
                if (rebuildAndLoad) {
                    long readyStarted = System.nanoTime();
                    indexManager.rebuild(documents);
                    long loadStarted = System.nanoTime();
                    upsertInBatches(store, documents);
                    upsertTimeMs = elapsedMs(loadStarted);
                    indexManager.awaitReady(documents.size(), Duration.ofMinutes(5));
                    indexBuildTimeMs = elapsedMs(readyStarted);
                }
                if (store.count() != documents.size()) throw new IllegalStateException("Vector count mismatch after readiness check");
                for (Map<String, Object> parameters : SearchParameterSweep.parametersForRun(
                        grids.get(scenarioIndex), scenario.runNumber() + repetition)) {
                    BenchmarkScenario measurement = scenario.measurement(repetition, parameters);
                    BenchmarkResult result;
                    try {
                        result = runScenario(store, indexManager, documents, queryPartition.calibration(),
                                queryPartition.evaluation(), measurement, groundTruth,
                                environment, indexBuildTimeMs, upsertTimeMs, buildId, rebuildAndLoad, contract);
                    } catch (RuntimeException exception) {
                        resultWriter.writeFailure(measurement, exception, properties.getResultDirectory());
                        throw exception;
                    }
                    // Persist each completed point, including low Recall, before attempting the next value.
                    artifacts = resultWriter.write(List.of(result), properties.getResultDirectory());
                    results.add(result);
                    if (result.audit().searchFailures() > 0) {
                        var failure = new IllegalStateException("A vector search failed; failed measurement and query audit preserved");
                        resultWriter.writeFailure(measurement, failure, properties.getResultDirectory());
                        throw failure;
                    }
                }
            }
        }
        return new RunOutput(List.copyOf(results), artifacts);
    }

    private BenchmarkResult runScenario(
            VectorStore store,
            VectorIndexManager indexManager,
            List<VectorDocument> documents,
            List<BenchmarkQuery> calibrationQueries,
            List<BenchmarkQuery> evaluationQueries,
            BenchmarkScenario scenario,
            Map<String, ExactGroundTruth> groundTruth,
            Map<String, Object> environment,
            long indexBuildTimeMs,
            long upsertTimeMs,
            String buildId,
            boolean rebuilt,
            ResponseContract contract
    ) {
        Map<String, Object> effectiveParameters = scenario.searchParameters();
        indexManager.configureSearch(effectiveParameters);
        List<Double> warmup = warmup(store, evaluationQueries, scenario, effectiveParameters);
        Map<String, Object> stabilityStateBefore = indexManager.diagnostics();

        LatencyCollector latency = new LatencyCollector();
        List<Callable<QueryOutcome>> tasks = new ArrayList<>();
        for (int pass = 0; pass < scenario.measurementIterations(); pass++) {
            for (BenchmarkQuery query : evaluationQueries) {
                boolean filtered = !query.filter().isEmpty();
                tasks.add(() -> {
                    VectorSearchRequest request = request(query, scenario, effectiveParameters);
                    long started = System.nanoTime();
                    try {
                        return new QueryOutcome(query, store.search(request), "");
                    } catch (RuntimeException failure) {
                        return new QueryOutcome(query, List.of(), failure.toString());
                    } finally {
                        latency.record(System.nanoTime() - started, filtered);
                    }
                });
            }
        }

        QuerySegment.Accumulator filteredScores = new QuerySegment.Accumulator();
        QuerySegment.Accumulator unfilteredScores = new QuerySegment.Accumulator();
        long benchmarkStarted;
        ResourceCollector.Usage resourceUsage;
        try (ResourceCollector.Measurement resources = resourceCollector.start(properties.getContainerNames());
             ExecutorService executor = Executors.newFixedThreadPool(scenario.concurrency())) {
            benchmarkStarted = System.nanoTime();
            List<Future<QueryOutcome>> futures = new ArrayList<>();
            long benchmarkEnded;
            do {
                // Repeat complete query batches so every query type keeps the same weight.
                futures.addAll(executor.invokeAll(tasks));
                benchmarkEnded = System.nanoTime();
            } while ((benchmarkEnded - benchmarkStarted < properties.getMinimumMeasurementTimeMs() * 1_000_000L
                    || (!properties.getContainerNames().isEmpty()
                        && resources.sampleCount(benchmarkStarted, benchmarkEnded) < properties.getMinimumResourceSamples()))
                    && benchmarkEnded - benchmarkStarted < properties.getMaximumMeasurementTimeMs() * 1_000_000L);
            long elapsed = benchmarkEnded - benchmarkStarted;
            resourceUsage = resources.usage(benchmarkStarted, benchmarkEnded);
            int searchFailures = 0;
            int invalidResponses = 0;
            Map<QueryAuditKey, Integer> queryAudit = new LinkedHashMap<>();
            // Recall is deliberately post-processed after the search workload timer closes.
            for (Future<QueryOutcome> future : futures) {
                QueryOutcome outcome = future.get();
                QuerySegment.Accumulator scores = !outcome.query().filter().isEmpty() ? filteredScores : unfilteredScores;
                ExactGroundTruth truth = groundTruth.get(outcome.query().queryId());
                List<String> violations = contract.violations(outcome.query(), outcome.approximate(), scenario.topK());
                if (!outcome.error().isEmpty()) searchFailures++;
                if (!violations.isEmpty()) invalidResponses++;
                Double tieRecall = null;
                Double strictRecall = null;
                if (!truth.strictTopK().isEmpty()) {
                    tieRecall = violations.isEmpty() ? recallCalculator.tieAwareRecallAtK(truth, outcome.approximate(), scenario.topK()) : 0;
                    strictRecall = violations.isEmpty() ? recallCalculator.recallAtK(truth.strictTopK(), outcome.approximate(), scenario.topK()) : 0;
                    scores.recordScored(tieRecall, strictRecall);
                } else {
                    // A filter that matches nothing has no ranking to reproduce; the store passes
                    // only by returning nothing, and that verdict stays out of the recall average.
                    scores.recordEmptyGroundTruth(recallCalculator.returnedNothing(outcome.approximate()));
                }
                queryAudit.merge(new QueryAuditKey(outcome.query().queryId(), outcome.approximate(), violations,
                        outcome.error(), tieRecall, strictRecall), 1, Integer::sum);
            }
            LatencyCollector.Statistics stats = latency.statistics();
            QuerySegment filtered = QuerySegment.of(latency.filteredStatistics(), filteredScores);
            QuerySegment unfiltered = QuerySegment.of(latency.unfilteredStatistics(), unfilteredScores);
            double recall = scoredRecall(filtered, unfiltered);
            double comparisonRecall = unfiltered.recall() == null ? recall : unfiltered.recall();
            double qps = (futures.size() - searchFailures) / (elapsed / 1_000_000_000.0);
            StabilityDiagnostics stability;
            try {
                stability = diagnoseStability(store, indexManager, calibrationQueries, scenario, groundTruth,
                        effectiveParameters, stabilityStateBefore);
            } catch (RuntimeException exception) {
                // Diagnostic failure does not erase a completed timed measurement.
                stability = new StabilityDiagnostics(true, false, properties.getDriftThreshold(), null,
                        List.of(), List.of(), stabilityStateBefore, Map.of(), "Diagnostic error: " + exception.getMessage());
            }
            return new BenchmarkResult(
                    scenario.testId(), scenario.runNumber(), store.database(), indexManager.engine(), indexManager.indexType(),
                    recall, comparisonRecall,
                    stats.averageMs(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), qps,
                    elapsed / 1_000_000L, resourceUsage.samples(),
                    filtered, unfiltered,
                    resourceUsage.averageCpuPercent(), resourceUsage.peakCpuPercent(),
                    resourceUsage.averageMemoryBytes(), resourceUsage.peakMemoryBytes(), resourceUsage.diskWriteBytes(),
                    indexManager.indexSizeBytes(), indexBuildTimeMs, upsertTimeMs, documents.size(), futures.size(),
                    scenario.concurrency(), scenario.topK(), scenario.warmupIterations(), scenario.measurementIterations(),
                    stability, indexManager.indexParameters(), effectiveParameters, environment, Instant.now(),
                    new MeasurementAudit(buildId, rebuilt, stabilityStateBefore, warmup, warmupStable(warmup),
                            !properties.getContainerNames().isEmpty() && resourceUsage.samples() >= properties.getMinimumResourceSamples()
                                    && resourceUsage.samples() > 0,
                            searchFailures, invalidResponses, resultWriter.writeQueryAudit(queryAudit, properties.getResultDirectory())));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("A vector search failed", exception.getCause());
        }
    }

    /** Mean recall over every scored search in both slices; queries with an empty exact result are excluded. */
    private static double scoredRecall(QuerySegment filtered, QuerySegment unfiltered) {
        int scored = filtered.scoredQueries() + unfiltered.scoredQueries();
        if (scored == 0) return 0;
        double total = 0;
        if (filtered.recall() != null) total += filtered.recall() * filtered.scoredQueries();
        if (unfiltered.recall() != null) total += unfiltered.recall() * unfiltered.scoredQueries();
        return total / scored;
    }

    private Map<String, ExactGroundTruth> exactGroundTruth(
            ExactSearchEngine exactSearch,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario
    ) {
        Map<String, ExactGroundTruth> groundTruth = new LinkedHashMap<>();
        for (BenchmarkQuery query : queries) {
            groundTruth.put(query.queryId(), exactSearch.groundTruth(request(query, scenario, Map.of()), properties.getTieTolerance()));
        }
        return Map.copyOf(groundTruth);
    }

    private VectorSearchRequest request(BenchmarkQuery query, BenchmarkScenario scenario, Map<String, Object> parameters) {
        return new VectorSearchRequest(query.embedding(), scenario.topK(), query.filter(), parameters);
    }

    private double recallUnderConcurrency(
            VectorStore store,
            List<BenchmarkQuery> queries,
            BenchmarkScenario scenario,
            Map<String, ExactGroundTruth> groundTruth,
            Map<String, Object> parameters,
            int concurrency
    ) {
        List<Callable<Double>> tasks = new ArrayList<>(queries.size() * scenario.measurementIterations());
        for (int pass = 0; pass < scenario.measurementIterations(); pass++) {
            for (BenchmarkQuery query : queries) {
                tasks.add(() -> {
                    ExactGroundTruth exact = groundTruth.get(query.queryId());
                    List<VectorSearchResult> approximate = store.search(request(query, scenario, parameters));
                    // Null keeps a query with no exact result out of the calibration average instead
                    // of contributing a free 1.0 to the diagnostic Recall average.
                    return !exact.strictTopK().isEmpty()
                            ? recallCalculator.tieAwareRecallAtK(exact, approximate, scenario.topK())
                            : null;
                });
            }
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Double> recalls = new ArrayList<>(tasks.size());
            for (Future<Double> future : executor.invokeAll(tasks)) {
                Double scored = future.get();
                if (scored != null) recalls.add(scored);
            }
            return recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark diagnostic was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("A vector search failed during diagnostics", exception.getCause());
        }
    }

    private StabilityDiagnostics diagnoseStability(
            VectorStore store,
            VectorIndexManager indexManager,
            List<BenchmarkQuery> calibrationQueries,
            BenchmarkScenario scenario,
            Map<String, ExactGroundTruth> groundTruth,
            Map<String, Object> parameters,
            Map<String, Object> stateBefore
    ) {
        if (!indexManager.requiresStabilityCheck()) return StabilityDiagnostics.notRequired();
        double threshold = properties.getDriftThreshold();
        int repetitions = properties.getDriftDiagnosticRepetitions();
        List<BenchmarkQuery> comparisonQueries = calibrationQueries.stream()
                .filter(query -> query.filter().isEmpty())
                .toList();
        if (comparisonQueries.isEmpty()) comparisonQueries = calibrationQueries;
        List<Double> serial = new ArrayList<>(repetitions);
        List<Double> concurrent = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            serial.add(recallUnderConcurrency(store, comparisonQueries, scenario, groundTruth, parameters, 1));
            concurrent.add(recallUnderConcurrency(
                    store, comparisonQueries, scenario, groundTruth, parameters, scenario.concurrency()));
        }
        double calibrationRecall = concurrent.getFirst();
        Map<String, Object> stateAfter = indexManager.diagnostics();
        double maximumCalibrationDrift = concurrent.stream()
                .mapToDouble(value -> Math.abs(value - calibrationRecall))
                .max().orElse(Double.POSITIVE_INFINITY);
        double concurrencyEffect = Math.abs(median(serial) - median(concurrent));
        boolean diagnosticStateComplete = !hasDiagnosticError(stateBefore) && !hasDiagnosticError(stateAfter);
        boolean verified = diagnosticStateComplete
                && maximumCalibrationDrift <= threshold
                && concurrencyEffect <= threshold;
        String reason = String.format(Locale.ROOT,
                "max calibration drift=%.6f, serial/concurrent median delta=%.6f, stateComplete=%s",
                maximumCalibrationDrift, concurrencyEffect, diagnosticStateComplete);
        return new StabilityDiagnostics(true, verified, threshold, calibrationRecall,
                serial, concurrent, stateBefore, stateAfter, reason);
    }

    private double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private boolean hasDiagnosticError(Map<String, Object> state) {
        return state.keySet().stream().anyMatch(key -> key.toLowerCase(Locale.ROOT).contains("error"));
    }

    private void upsertInBatches(VectorStore store, List<VectorDocument> documents) {
        int batchSize = properties.getUpsertBatchSize();
        if (batchSize < 1) throw new IllegalStateException("benchmark.upsert-batch-size must be positive");
        for (int start = 0; start < documents.size(); start += batchSize) {
            store.upsert(documents.subList(start, Math.min(start + batchSize, documents.size())));
        }
    }

    private void validateScenarios(List<BenchmarkScenario> scenarios, VectorStore store, VectorIndexManager manager) {
        if (properties.getMaximumMeasurementTimeMs() < properties.getMinimumMeasurementTimeMs()
                || properties.getMaximumMeasurementTimeMs() > 600_000 || properties.getMaximumMeasurementTimeMs() < 1) {
            throw new IllegalStateException("maximum-measurement-time-ms must be >= minimum and in [1, 600000]");
        }
        if (properties.getMinimumResourceSamples() < 0 || properties.getMinimumResourceSamples() > 1000
                || properties.getWarmupMaximumPasses() < 3 || properties.getWarmupMaximumPasses() > 100
                || !Double.isFinite(properties.getWarmupRelativeTolerance()) || properties.getWarmupRelativeTolerance() < 0
                || !Double.isFinite(properties.getTieTolerance()) || properties.getTieTolerance() < 0) {
            throw new IllegalStateException("Invalid telemetry/warmup/tie tolerance configuration");
        }
        if (properties.getMinimumMeasurementTimeMs() < 0 || properties.getMinimumMeasurementTimeMs() > 600_000) {
            throw new IllegalStateException("benchmark.minimum-measurement-time-ms must be in [0, 600000]");
        }
        if (properties.getDriftDiagnosticRepetitions() < 1 || properties.getDriftDiagnosticRepetitions() > 10) {
            throw new IllegalStateException("benchmark.drift-diagnostic-repetitions must be in [1, 10]");
        }
        if (!Double.isFinite(properties.getDriftThreshold())
                || properties.getDriftThreshold() < 0 || properties.getDriftThreshold() > 1) {
            throw new IllegalStateException("benchmark.drift-threshold must be in [0, 1]");
        }
        for (BenchmarkScenario scenario : scenarios) {
            if (scenario.database() != null && !scenario.database().isBlank() && !scenario.database().equalsIgnoreCase(store.database())) {
                throw new IllegalArgumentException("Scenario database does not match active store: " + store.database());
            }
            if (scenario.indexType() != null && !scenario.indexType().isBlank() && !scenario.indexType().equalsIgnoreCase(manager.indexType())) {
                throw new IllegalArgumentException("Scenario indexType does not match active index: " + manager.indexType());
            }
            if (scenario.engine() != null && !scenario.engine().isBlank() && !scenario.engine().equalsIgnoreCase(manager.engine())) {
                throw new IllegalArgumentException("Scenario engine does not match active engine: " + manager.engine());
            }
        }
    }

    private void validateDimensions(List<VectorDocument> documents, List<BenchmarkQuery> queries, VectorStore store) {
        if (properties.getMetric() == com.myapp.domain.vector.DistanceMetric.COSINE
                && (documents.stream().anyMatch(d -> zero(d.embedding())) || queries.stream().anyMatch(q -> zero(q.embedding())))) {
            throw new IllegalArgumentException("Cosine vectors must have a nonzero norm");
        }
        int dimension = documents.getFirst().embedding().length;
        if (queries.stream().anyMatch(query -> query.embedding().length != dimension)) {
            throw new IllegalArgumentException("Document vectors and query vectors must use the same dimension");
        }
        if (dimension != store.dimension()) {
            throw new IllegalArgumentException("Input vector dimension " + dimension
                    + " does not match active store dimension " + store.dimension());
        }
        if (properties.getMetric() != store.metric()) {
            throw new IllegalArgumentException("Benchmark metric " + properties.getMetric()
                    + " does not match active store metric " + store.metric());
        }
    }

    private VectorStore requiredStore() {
        VectorStore store = storeProvider.getIfAvailable();
        if (store == null) throw new IllegalStateException("No vector store is active. Enable a vector DB Spring profile.");
        return store;
    }

    private VectorIndexManager requiredIndexManager() {
        VectorIndexManager manager = indexManagerProvider.getIfAvailable();
        if (manager == null) throw new IllegalStateException("No vector index manager is active.");
        return manager;
    }

    private long elapsedMs(long startedNanoseconds) {
        return (System.nanoTime() - startedNanoseconds) / 1_000_000;
    }

    public record RunOutput(List<BenchmarkResult> results, ResultWriter.Artifacts artifacts) {
    }


    private boolean zero(float[] vector) {
        for (float value : vector) if (value != 0) return false;
        return true;
    }

    private List<Double> warmup(VectorStore store, List<BenchmarkQuery> queries, BenchmarkScenario scenario,
                                Map<String, Object> parameters) {
        List<Double> p95 = new ArrayList<>();
        if (scenario.warmupIterations() == 0) return p95;
        try (ExecutorService executor = Executors.newFixedThreadPool(scenario.concurrency())) {
            int minimum = Math.max(3, scenario.warmupIterations());
            for (int pass = 0; pass < Math.max(minimum, properties.getWarmupMaximumPasses()); pass++) {
                LatencyCollector latency = new LatencyCollector();
                List<Callable<Void>> tasks = queries.stream().<Callable<Void>>map(query -> () -> {
                    long started = System.nanoTime();
                    store.search(request(query, scenario, parameters));
                    latency.record(System.nanoTime() - started, !query.filter().isEmpty());
                    return null;
                }).toList();
                for (Future<Void> future : executor.invokeAll(tasks)) future.get();
                p95.add(latency.statistics().p95Ms());
                if (p95.size() >= minimum && warmupStable(p95)) break;
            }
            return List.copyOf(p95);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warmup interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Warmup search failed", exception.getCause());
        }
    }

    private boolean warmupStable(List<Double> values) {
        if (values.size() < 3) return false;
        List<Double> last = values.subList(values.size() - 3, values.size()).stream().sorted().toList();
        return last.getLast() - last.getFirst() <= Math.max(last.get(1), .000001) * properties.getWarmupRelativeTolerance();
    }

    private record QueryOutcome(BenchmarkQuery query, List<VectorSearchResult> approximate, String error) {
    }

    public record QueryAuditKey(String queryId, List<VectorSearchResult> results, List<String> violations,
                               String searchError, Double tieAwareRecall, Double strictIdRecall) {
    }

}
