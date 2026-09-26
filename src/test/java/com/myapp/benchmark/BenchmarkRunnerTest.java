package com.myapp.benchmark;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.rdb.postgres.BenchmarkSourceOfTruthSynchronizer;
import com.myapp.port.VectorIndexManager;
import com.myapp.port.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BenchmarkRunnerTest {
    @TempDir Path directory;
    private final VectorStore store = mock(VectorStore.class);
    private final VectorIndexManager manager = mock(VectorIndexManager.class, CALLS_REAL_METHODS);

    @Test
    void measuresEveryParameterAndEveryRepetitionIncludingLowAndHighRecall() throws Exception {
        BenchmarkRunner runner = runner();
        var output = runner.run(List.of(scenario(3, List.of(16, 32, 64, 96, 128))), false);
        assertThat(output.results()).hasSize(15);
        assertThat(output.results()).extracting(BenchmarkResult::runNumber)
                .containsExactly(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3);
        assertThat(output.results()).extracting(BenchmarkResult::actualRecall).contains(0.8, 0.9, 1.0);
        for (int parameter : List.of(16, 32, 64, 96, 128)) {
            assertThat(output.results().stream().filter(r -> r.searchParameters().equals(Map.of("ef", parameter))))
                    .hasSize(3).allSatisfy(r -> {
                        assertThat(r.queryExecutions()).isEqualTo(4);
                        assertThat(r.p95LatencyMs()).isPositive();
                        assertThat(r.qps()).isPositive();
                    });
        }
        assertThat(Files.readAllLines(output.artifacts().csv())).hasSize(16);
        String chart = Files.readString(output.artifacts().chart());
        assertThat(chart.split("class=\"point\"", -1)).hasSize(16);
        assertThat(chart).contains("data-recall=\"0.80000000\"", "data-recall=\"1.00000000\"");
        var summary = BenchmarkSummary.aggregate(output.results());
        assertThat(summary).hasSize(5).allSatisfy(group -> assertThat(group.completedMeasurements()).isEqualTo(3));
    }

    @Test
    void preservesCompletedMeasurementsIfALaterSearchFails() throws Exception {
        BenchmarkRunner runner = runner();
        var order = SearchParameterSweep.parametersForRun(List.of(Map.of("ef", 16), Map.of("ef", 32), Map.of("ef", 64)), 1);
        int failingParameter = (int) order.get(1).get("ef");
        doAnswer(invocation -> {
            VectorSearchRequest request = invocation.getArgument(0);
            if (request.searchParameters().get("ef").equals(failingParameter)) throw new IllegalStateException("simulated search error");
            return matches(8);
        }).when(store).search(any());
        assertThatThrownBy(() -> runner.run(List.of(scenario(1, List.of(16, 32, 64))), false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vector search failed");
        assertThat(Files.readAllLines(directory.resolve("results/csv/vector-db-result.csv"))).hasSize(3);
        assertThat(Files.readString(directory.resolve("results/charts/recall-latency-latest.svg")))
                .contains("2 measured points", "data-recall=\"0.80000000\"");
        try (var failures = Files.list(directory.resolve("results/failures"))) {
            assertThat(failures.count()).isEqualTo(1);
        }
    }

    @Test
    void rejectsUnsupportedGridBeforeMutatingTheIndex() throws Exception {
        BenchmarkRunner runner = runner();
        assertThatThrownBy(() -> runner.run(List.of(scenario(1, List.of(16, 256))), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported parameter range");
        verify(manager, never()).rebuild(any());
        verify(store, never()).search(any());
    }

    @Test
    void fixedParameterStillGetsIndependentRepeatedMeasurements() throws Exception {
        BenchmarkRunner runner = runner();
        var fixed = new BenchmarkScenario("T01", 1, 3, "fake", "Native", "HNSW",
                10, 2, 0, 2, Map.of("ef", 16), List.of());
        assertThat(runner.run(List.of(fixed), false).results()).hasSize(3)
                .allSatisfy(r -> assertThat(r.searchParameters()).isEqualTo(Map.of("ef", 16)));
    }

    @Test
    void rebuildsEveryRepetitionAndRecordsDistinctBuildIds() throws Exception {
        var output = runner().run(List.of(scenario(3, List.of(16))), true);
        verify(manager, times(3)).rebuild(any());
        verify(store, times(3)).upsert(any());
        assertThat(output.results().stream().map(r -> r.audit().buildId()).distinct()).hasSize(3);
        assertThat(output.results()).allSatisfy(r -> {
            assertThat(r.audit().rebuilt()).isTrue();
            assertThat(directory.resolve("results").resolve(r.audit().queryAuditFile())).exists();
        });
    }

    @Test
    void nonRebuildCostsAreUnavailableAndExploratoryResultsCannotPassFinalGate() throws Exception {
        var output = runner().run(List.of(scenario(1, List.of(16))), false);
        assertThat(output.results().getFirst().indexBuildTimeMs()).isEqualTo(-1);
        assertThat(output.results().getFirst().upsertTimeMs()).isEqualTo(-1);
        var group = BenchmarkSummary.aggregate(output.results()).getFirst();
        assertThat(group.metrics().get("time_to_index_ready_ms").samples()).isZero();
        assertThat(group.decision().eligible()).isFalse();
        assertThat(group.decision().reasons()).contains("exploratory-not-independent-holdout", "index-not-rebuilt");
    }

    private BenchmarkScenario scenario(int repetitions, List<Integer> values) {
        return new BenchmarkScenario("T01", 1, repetitions, "fake", "Native", "HNSW",
                10, 2, 0, 2, Map.of(), values);
    }

    @Test
    void extendsShortMeasurementsWithWholeQueryBatchesAndCountsEverySearch() throws Exception {
        var output = runner(60).run(List.of(scenario(1, List.of(16))), false);
        var result = output.results().getFirst();
        assertThat(result.measurementTimeMs()).isGreaterThanOrEqualTo(60);
        assertThat(result.queryExecutions()).isGreaterThan(4);
        assertThat(result.queryExecutions() % 4).isZero();
        assertThat(result.unfiltered().queryExecutions()).isEqualTo(result.queryExecutions());
        assertThat(result.actualRecall()).isCloseTo(.8, org.assertj.core.data.Offset.offset(1e-9));
        verify(manager, times(1)).configureSearch(Map.of("ef", 16));
    }

    private BenchmarkRunner runner() throws Exception {
        return runner(0);
    }

    private BenchmarkRunner runner(long minimumTimeMs) throws Exception {
        Path documents = directory.resolve("documents.jsonl");
        Files.writeString(documents, String.join("\n", IntStream.range(0, 10)
                .mapToObj(id -> "{\"id\":\"d" + id + "\",\"embedding\":[1,0]}").toList()));
        Path queries = directory.resolve("queries.jsonl");
        Files.writeString(queries, """
                {"queryId":"q1","embedding":[1,0]}
                {"queryId":"q2","embedding":[1,0]}
                {"queryId":"q3","embedding":[1,0]}
                """);
        BenchmarkProperties properties = new BenchmarkProperties();
        properties.setDocumentVectors(documents);
        properties.setQueryVectors(queries);
        properties.setQueryDefinitions(queries);
        properties.setCalibrationQueryCount(1);
        properties.setMinimumMeasurementTimeMs(minimumTimeMs);
        properties.setResultDirectory(directory.resolve("results"));
        when(store.database()).thenReturn("fake");
        when(store.dimension()).thenReturn(2);
        when(store.metric()).thenReturn(DistanceMetric.COSINE);
        when(store.count()).thenReturn(10L);
        when(manager.indexType()).thenReturn("HNSW");
        when(manager.searchParameterName()).thenReturn("ef");
        when(manager.maximumSearchParameter()).thenReturn(128);
        AtomicInteger configured = new AtomicInteger();
        doAnswer(invocation -> {
            Map<String, Object> parameters = invocation.getArgument(0);
            configured.set((int) parameters.get("ef"));
            return null;
        }).when(manager).configureSearch(any());
        when(store.search(any())).thenAnswer(invocation -> {
            VectorSearchRequest request = invocation.getArgument(0);
            int parameter = (int) request.searchParameters().get("ef");
            assertThat(configured.get()).isEqualTo(parameter);
            return matches(parameter == 16 ? 8 : parameter == 32 ? 9 : 10);
        });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("store", store);
        beans.registerSingleton("manager", manager);
        return new BenchmarkRunner(beans.getBeanProvider(VectorStore.class), beans.getBeanProvider(VectorIndexManager.class),
                beans.getBeanProvider(BenchmarkSourceOfTruthSynchronizer.class), properties, JsonMapper.builder().build());
    }

    private List<VectorSearchResult> matches(int count) {
        return IntStream.range(0, count).mapToObj(id -> new VectorSearchResult("d" + id, "d" + id, "d" + id, 1)).toList();
    }
}
