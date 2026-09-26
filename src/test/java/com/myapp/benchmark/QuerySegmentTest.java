package com.myapp.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QuerySegmentTest {
    @Test
    void averagesRecallOverTheScoredSearches() {
        QuerySegment.Accumulator accumulator = new QuerySegment.Accumulator();
        accumulator.recordScored(1.0);
        accumulator.recordScored(0.8);
        accumulator.recordScored(0.6);

        QuerySegment segment = QuerySegment.of(new LatencyCollector.Statistics(3, 10, 8, 20, 25), accumulator);

        assertThat(segment.queryExecutions()).isEqualTo(3);
        assertThat(segment.scoredQueries()).isEqualTo(3);
        assertThat(segment.recall()).isCloseTo(0.8, within(1e-9));
        assertThat(segment.p95Ms()).isEqualTo(20);
        assertThat(segment.emptyGroundTruthQueries()).isZero();
    }

    @Test
    void keepsEmptyGroundTruthSearchesOutOfTheRecallAverage() {
        QuerySegment.Accumulator accumulator = new QuerySegment.Accumulator();
        accumulator.recordScored(0.5);
        accumulator.recordEmptyGroundTruth(true);
        accumulator.recordEmptyGroundTruth(true);

        QuerySegment segment = QuerySegment.of(new LatencyCollector.Statistics(3, 10, 8, 20, 25), accumulator);

        // Folding the two empty-ground-truth searches in as 1.0 would report 0.833 instead of 0.5.
        assertThat(segment.recall()).isCloseTo(0.5, within(1e-9));
        assertThat(segment.queryExecutions()).isEqualTo(3);
        assertThat(segment.scoredQueries()).isEqualTo(1);
        assertThat(segment.emptyGroundTruthQueries()).isEqualTo(2);
        assertThat(segment.emptyGroundTruthViolations()).isZero();
    }

    @Test
    void countsRowsReturnedForAnEmptyGroundTruthAsAViolation() {
        QuerySegment.Accumulator accumulator = new QuerySegment.Accumulator();
        accumulator.recordEmptyGroundTruth(false);
        accumulator.recordEmptyGroundTruth(true);

        QuerySegment segment = QuerySegment.of(new LatencyCollector.Statistics(2, 10, 8, 20, 25), accumulator);

        assertThat(segment.recall()).isNull();
        assertThat(segment.emptyGroundTruthQueries()).isEqualTo(2);
        assertThat(segment.emptyGroundTruthViolations()).isEqualTo(1);
    }

    @Test
    void reportsEmptyWhenTheSegmentHasNoQuery() {
        assertThat(QuerySegment.of(LatencyCollector.Statistics.EMPTY, new QuerySegment.Accumulator()))
                .isEqualTo(QuerySegment.EMPTY);
        assertThat(QuerySegment.EMPTY.recall()).isNull();
    }
}
