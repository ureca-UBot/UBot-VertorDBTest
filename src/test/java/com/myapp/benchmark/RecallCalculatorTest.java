package com.myapp.benchmark;

import com.myapp.domain.vector.VectorSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallCalculatorTest {
    private final RecallCalculator calculator = new RecallCalculator();

    @Test
    void calculatesIntersectionOverExactTopK() {
        List<VectorSearchResult> exact = List.of(result("a"), result("b"), result("c"), result("d"));
        List<VectorSearchResult> approximate = List.of(result("a"), result("x"), result("c"), result("y"));

        assertThat(calculator.recallAtK(exact, approximate, 4)).isEqualTo(0.5);
    }

    @Test
    void doesNotPenalizeDatasetSmallerThanK() {
        assertThat(calculator.recallAtK(List.of(result("a"), result("b")), List.of(result("a"), result("b")), 10))
                .isEqualTo(1.0);
    }

    private VectorSearchResult result(String id) {
        return new VectorSearchResult(id, id, id, 1);
    }
}
