package com.myapp.benchmark;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorFilter;
import com.myapp.domain.vector.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExactSearchEngineTest {
    private final List<VectorDocument> documents = List.of(
            new VectorDocument("a", "doc-a", "a", "A", new float[]{1, 0}, Map.of("tenant", "one")),
            new VectorDocument("b", "doc-b", "b", "B", new float[]{0.8f, 0.2f}, Map.of("tenant", "one")),
            new VectorDocument("c", "doc-c", "c", "C", new float[]{0, 1}, Map.of("tenant", "two"))
    );

    @Test
    void returnsExactCosineOrder() {
        ExactSearchEngine engine = new ExactSearchEngine(documents, DistanceMetric.COSINE);

        var results = engine.search(new VectorSearchRequest(new float[]{1, 0}, 2, VectorFilter.NONE));

        assertThat(results).extracting(result -> result.id()).containsExactly("a", "b");
    }

    @Test
    void appliesMetadataFilterBeforeRanking() {
        ExactSearchEngine engine = new ExactSearchEngine(documents, DistanceMetric.COSINE);

        var results = engine.search(new VectorSearchRequest(
                new float[]{1, 0}, 10, new VectorFilter(Map.of("tenant", "two"))));

        assertThat(results).extracting(result -> result.id()).containsExactly("c");
    }
}
