package com.myapp.benchmark;

import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPartitionerTest {
    @Test
    void createsDeterministicStratifiedOneHundredTwoHundredSplit() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("short_clear", 60);
        distribution.put("long_natural", 60);
        distribution.put("technical_term", 45);
        distribution.put("semantic_paraphrase", 45);
        distribution.put("exact_identifier", 30);
        distribution.put("ambiguous", 30);
        distribution.put("metadata_filter", 30);
        List<BenchmarkQuery> queries = queries(distribution);

        QueryPartitioner.Partition first = QueryPartitioner.split(queries, 100);
        QueryPartitioner.Partition second = QueryPartitioner.split(queries, 100);

        assertThat(first.calibration()).hasSize(100);
        assertThat(first.evaluation()).hasSize(200);
        assertThat(countByType(first.calibration())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "short_clear", 20, "long_natural", 20, "technical_term", 15,
                "semantic_paraphrase", 15, "exact_identifier", 10, "ambiguous", 10,
                "metadata_filter", 10));
        assertThat(first.calibrationIdsSha256()).isEqualTo(second.calibrationIdsSha256());
        assertThat(first.evaluationIdsSha256()).isEqualTo(second.evaluationIdsSha256());
        assertThat(first.calibration()).extracting(BenchmarkQuery::queryId)
                .doesNotContainAnyElementsOf(first.evaluation().stream().map(BenchmarkQuery::queryId).toList());
    }

    private List<BenchmarkQuery> queries(Map<String, Integer> distribution) {
        List<BenchmarkQuery> queries = new ArrayList<>();
        int id = 1;
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            for (int index = 0; index < entry.getValue(); index++) {
                queries.add(new BenchmarkQuery("q-%03d".formatted(id++), "query", entry.getKey(), true,
                        new float[]{1}, VectorFilter.NONE));
            }
        }
        return queries;
    }

    private Map<String, Integer> countByType(List<BenchmarkQuery> queries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        queries.forEach(query -> counts.merge(query.queryType(), 1, Integer::sum));
        return counts;
    }
}
