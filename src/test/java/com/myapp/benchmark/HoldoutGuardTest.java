package com.myapp.benchmark;

import com.myapp.dataset.QuerySetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HoldoutGuardTest {
    @TempDir Path dir;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BenchmarkScenario fixed = new BenchmarkScenario("T05", 1, 1, "qdrant", "Native", "HNSW",
            10, 10, 1, 5, Map.of("hnsw_ef", 100), List.of());

    @Test void validatesFrozenInputsAndUsesOnlyPriorQueriesForDiagnostics() throws Exception {
        var properties = fixture("q-new", "new question", "[0,1]");
        var guard = new HoldoutGuard(mapper);
        assertThat(validate(guard, properties)).containsEntry("status", "holdout-validated");
        assertThat(guard.calibrationQueries(properties)).extracting(q -> q.queryId()).containsExactly("q-old");
        Files.writeString(properties.getQueryVectors(), "{}\n");
        assertThatThrownBy(() -> guard.validate(properties, List.of(fixed), List.of(), Map.of("m", 16)))
                .hasMessageContaining("queryVectorsSha256");
    }

    @Test void rejectsPreviouslySeenIdsTextAndVectors() throws Exception {
        for (String[] row : List.of(new String[]{"q-old", "new", "[0,1]"},
                new String[]{"q-new", " OLD   question ", "[0,1]"}, new String[]{"q-new", "new", "[1,0]"})) {
            var p = fixture(row[0], row[1], row[2]);
            assertThatThrownBy(() -> validate(new HoldoutGuard(mapper), p)).hasMessageContaining("used for selection");
        }
    }

    @Test void refusesGridOrChangedConfigurationAndUnknownMode() throws Exception {
        var p = fixture("q-new", "new", "[0,1]");
        var guard = new HoldoutGuard(mapper);
        var queries = new QuerySetLoader(mapper).load(p.getQueryDefinitions(), p.getQueryVectors());
        assertThatThrownBy(() -> guard.validate(p, List.of(fixed), queries, Map.of("m", 32))).hasMessageContaining("indexParameters");
        var grid = new BenchmarkScenario("T05", 1, 1, "qdrant", "Native", "HNSW", 10, 10, 1, 5, Map.of(), List.of(100));
        assertThatThrownBy(() -> guard.validate(p, List.of(grid), queries, Map.of("m", 16))).hasMessageContaining("fixed");
        p.setMode("typo");
        assertThatThrownBy(() -> validate(guard, p)).hasMessageContaining("mode");
    }

    private Map<String, Object> validate(HoldoutGuard guard, BenchmarkProperties p) {
        return guard.validate(p, List.of(fixed), new QuerySetLoader(mapper).load(p.getQueryDefinitions(), p.getQueryVectors()), Map.of("m", 16));
    }
    private BenchmarkProperties fixture(String id, String text, String vector) throws Exception {
        Path prior = dir.resolve("prior.jsonl"), queries = dir.resolve("queries.jsonl"), docs = dir.resolve("docs.jsonl");
        Files.writeString(prior, "{\"queryId\":\"q-old\",\"query\":\"old question\",\"embedding\":[1,0]}\n");
        Files.writeString(queries, "{\"queryId\":\"" + id + "\",\"query\":\"" + text + "\",\"embedding\":" + vector + "}\n");
        Files.writeString(docs, "{\"id\":\"d\",\"embedding\":[1,0]}\n");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("documentVectorsSha256", BenchmarkEnvironmentCollector.sha256(docs));
        plan.put("queryVectorsSha256", BenchmarkEnvironmentCollector.sha256(queries));
        plan.put("queryDefinitionsSha256", BenchmarkEnvironmentCollector.sha256(queries));
        plan.put("priorQueryVectors", "prior.jsonl"); plan.put("priorQueryDefinitions", "prior.jsonl");
        plan.put("priorQueryVectorsSha256", BenchmarkEnvironmentCollector.sha256(prior));
        plan.put("priorQueryDefinitionsSha256", BenchmarkEnvironmentCollector.sha256(prior));
        plan.put("indexParameters", Map.of("m", 16));
        plan.put("scenarios", List.of(fixed));
        plan.put("thresholds", Map.of("recallMinimum", .95, "p95MaximumMs", 30, "ramMaximumBytes", 2147483648L, "errorRateMaximum", 0));
        Path path = dir.resolve("plan.json"); Files.writeString(path, mapper.writeValueAsString(plan));
        BenchmarkProperties p = new BenchmarkProperties(); p.setMode("holdout"); p.setValidationPlan(path);
        p.setDocumentVectors(docs); p.setQueryVectors(queries); p.setQueryDefinitions(queries);
        return p;
    }
}
