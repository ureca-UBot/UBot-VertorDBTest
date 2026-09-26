package com.myapp.benchmark;

import com.myapp.domain.vector.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FairRecallTest {
    private VectorDocument doc(String id, float x, float y) {
        return new VectorDocument(id, id, id, "", new float[]{x, y}, Map.of("tenant", "Alpha"));
    }
    private VectorSearchResult hit(String id) { return new VectorSearchResult(id, id, id, 1); }

    @Test void acceptsAllBoundaryTiesButDoesNotCompensateForMissingBetterNeighbors() {
        var docs = List.of(doc("best", 1, 0), doc("a", 1, 1), doc("b", 1, 1), doc("c", 1, 1));
        var truth = new ExactSearchEngine(docs, DistanceMetric.COSINE).groundTruth(
                new VectorSearchRequest(new float[]{1, 0}, 2, VectorFilter.NONE, Map.of()), 0);
        assertThat(truth.strictlyBetterIds()).containsExactly("best");
        assertThat(truth.boundaryIds()).containsExactlyInAnyOrder("a", "b", "c");
        var calculator = new RecallCalculator();
        assertThat(calculator.tieAwareRecallAtK(truth, List.of(hit("best"), hit("c")), 2)).isEqualTo(1);
        assertThat(calculator.recallAtK(truth.strictTopK(), List.of(hit("best"), hit("c")), 2)).isEqualTo(.5);
        assertThat(calculator.tieAwareRecallAtK(truth, List.of(hit("b"), hit("c")), 2)).isEqualTo(.5);
        assertThat(calculator.tieAwareRecallAtK(truth, List.of(hit("best"), hit("best")), 2)).isEqualTo(.5);
    }

    @Test void filteredTruthUsesEligibleCountAndNoHitRecallIsUndefined() {
        var engine = new ExactSearchEngine(List.of(doc("a", 1, 0)), DistanceMetric.COSINE);
        var truth = engine.groundTruth(new VectorSearchRequest(new float[]{1, 0}, 10, VectorFilter.NONE, Map.of()), 0);
        assertThat(new RecallCalculator().tieAwareRecallAtK(truth, List.of(hit("a")), 10)).isEqualTo(1);
        var empty = engine.groundTruth(new VectorSearchRequest(new float[]{1, 0}, 10,
                new VectorFilter(Map.of("tenant", "alpha")), Map.of()), 0);
        assertThat(empty.strictTopK()).isEmpty();
        assertThatThrownBy(() -> new RecallCalculator().tieAwareRecallAtK(empty, List.of(), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void responseContractRejectsDuplicateUnknownIdentityAndFilterErrors() {
        var contract = new ResponseContract(List.of(doc("a", 1, 0)));
        var query = new BenchmarkQuery("q", "", "", false, new float[]{1, 0}, new VectorFilter(Map.of("tenant", "alpha")));
        assertThat(contract.violations(query, List.of(hit("a"), hit("a"), hit("unknown")), 2))
                .anyMatch(e -> e.contains("duplicate")).anyMatch(e -> e.contains("unknown"))
                .anyMatch(e -> e.contains("filter")).anyMatch(e -> e.contains("topK"));
        assertThat(contract.violations(query, List.of(new VectorSearchResult("a", "wrong", "a", 1)), 2))
                .anyMatch(e -> e.contains("identity"));
        assertThat(new VectorFilter(Map.of("n", 2)).matches(Map.of("n", 2.0))).isTrue();
        assertThat(new VectorFilter(Map.of("n", "2")).matches(Map.of("n", 2))).isFalse();
    }
}
