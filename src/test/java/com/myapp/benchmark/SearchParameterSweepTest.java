package com.myapp.benchmark;

import com.myapp.port.VectorIndexManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchParameterSweepTest {
    private final VectorIndexManager manager = new VectorIndexManager() {
        public String indexType() { return "HNSW"; }
        public String searchParameterName() { return "hnsw_ef"; }
        public int maximumSearchParameter() { return 1000; }
        public void create() { }
        public void drop() { }
    };

    @Test
    void rejectsWrongMissingAndAdditionalKeysInsteadOfMeasuringAnAdapterDefault() {
        for (Map<String, Object> parameters : List.of(
                Map.<String, Object>of("ef", 20),
                Map.<String, Object>of("hnsw_ef_typo", 20),
                Map.<String, Object>of("hnsw_ef", 20, "ef", 100))) {
            assertThatThrownBy(() -> SearchParameterSweep.parameters(scenario(parameters), manager, List.of(10)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only hnsw_ef");
        }
    }

    @Test
    void rejectsFractionalNonNumericNonFiniteAndOverflowingValues() {
        for (Object value : List.of("20", 20.1, new BigDecimal("20.000000000000000000001"),
                Double.NaN, Double.POSITIVE_INFINITY, new BigInteger("4294967316"))) {
            assertThatThrownBy(() -> SearchParameterSweep.parameters(scenario(Map.of("hnsw_ef", value)), manager, List.of(10)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer");
        }
    }

    @Test
    void normalizesEquivalentNumbersAndChecksSupportedRange() {
        for (Object value : List.of(20L, 20.0, new BigDecimal("20.000"), BigInteger.valueOf(20))) {
            assertThat(SearchParameterSweep.parameters(scenario(Map.of("hnsw_ef", value)), manager, List.of(10)))
                    .containsExactly(Map.of("hnsw_ef", 20));
        }
        for (int value : List.of(9, 1001)) {
            assertThatThrownBy(() -> SearchParameterSweep.parameters(scenario(Map.of("hnsw_ef", value)), manager, List.of(10)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported parameter range");
        }
    }

    @Test
    void runSpecificOrderIsReproducibleAndKeepsEveryParameterWithoutMutatingTheGrid() {
        List<Map<String, Object>> grid = List.of(10, 20, 40, 80, 120, 200, 400, 800, 1000).stream()
                .map(value -> Map.<String, Object>of("hnsw_ef", value)).toList();
        var first = SearchParameterSweep.parametersForRun(grid, 1);
        assertThat(first).isEqualTo(SearchParameterSweep.parametersForRun(grid, 1));
        assertThat(first).isNotEqualTo(SearchParameterSweep.parametersForRun(grid, 2));
        for (int run = 1; run <= 5; run++) {
            assertThat(SearchParameterSweep.parametersForRun(grid, run)).containsExactlyInAnyOrderElementsOf(grid);
        }
        assertThat(grid.getFirst()).isEqualTo(Map.of("hnsw_ef", 10));
        assertThat(SearchParameterSweep.parametersForRun(List.of(Map.of("hnsw_ef", 20)), 3))
                .containsExactly(Map.of("hnsw_ef", 20));
    }

    private BenchmarkScenario scenario(Map<String, Object> parameters) {
        return new BenchmarkScenario("T05", 1, 1, "qdrant", "Native", "HNSW", 10, 10, 0, 1,
                parameters, List.of());
    }
}
