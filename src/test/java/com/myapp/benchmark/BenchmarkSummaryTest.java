package com.myapp.benchmark;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSummaryTest {
    @Test
    void groupsFixedParametersAndWorkloadsInsteadOfTestIds() {
        var groups = BenchmarkSummary.aggregate(List.of(
                result("T01", 16, "hash-a", 2, 3), result("T02", 16, "hash-a", 2, 5),
                result("T01", 32, "hash-a", 2, 8), result("T01", 16, "hash-b", 2, 9),
                result("T01", 16, "hash-a", 4, 7)));
        assertThat(groups).hasSize(4);
        assertThat(groups.getFirst().completedMeasurements()).isEqualTo(2);
        var p95 = groups.getFirst().metrics().get("p95_ms");
        assertThat(p95.mean()).isEqualTo(4);
        assertThat(p95.median()).isEqualTo(4);
        assertThat(p95.p95()).isEqualTo(5);
        assertThat(p95.p99()).isEqualTo(5);
        assertThat(p95.sampleVariance()).isEqualTo(2);
    }

    @Test
    void missingResourcesAreNotZeroOrNegativeEfficiencyMeasurements() {
        var missing = BenchmarkSummary.Distribution.of(List.of(-1.0, -1.0));
        assertThat(missing.samples()).isZero();
        assertThat(missing.mean()).isNull();
        var partial = BenchmarkSummary.Distribution.of(List.of(-1.0, 40.0));
        assertThat(partial.samples()).isEqualTo(1);
        assertThat(partial.mean()).isEqualTo(40);
        assertThat(partial.sampleVariance()).isNull();
    }

    private BenchmarkResult result(String id, int ef, String hash, int concurrency, double p95) {
        return new BenchmarkResult(id, 1, "fake", "Native", "HNSW", .943, .943,
                1, 1, p95, 10, 100, 5000, 2, QuerySegment.EMPTY, QuerySegment.EMPTY,
                -1, -1, -1, -1, -1, -1, 10, 5, 100, 100, concurrency, 10, 1, 5,
                StabilityDiagnostics.notRequired(), Map.of("m", 16), Map.of("ef", ef),
                Map.of("documentVectorsSha256", hash), Instant.now(), null);
    }
}
