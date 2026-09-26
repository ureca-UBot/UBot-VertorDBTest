package com.myapp.benchmark;

import com.myapp.domain.vector.VectorSearchResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecallCalculator {

    public double tieAwareRecallAtK(ExactGroundTruth truth, List<VectorSearchResult> approximate, int k) {
        int denominator = Math.min(k, truth.strictTopK().size());
        if (denominator == 0) throw new IllegalArgumentException("Empty ground truth has no Recall");
        Set<String> returned = new HashSet<>();
        approximate.stream().limit(k).map(VectorSearchResult::id).forEach(returned::add);
        long better = returned.stream().filter(truth.strictlyBetterIds()::contains).count();
        long boundary = returned.stream().filter(truth.boundaryIds()::contains).count();
        return (better + Math.min(denominator - truth.strictlyBetterIds().size(), boundary)) / (double) denominator;
    }

    /**
     * Recall@K against the exact top-K.
     *
     * <p>Callers must keep queries with an empty exact result out of the recall average and score
     * them with {@link #returnedNothing} instead — a query whose filter matches no document has no
     * ranking to reproduce, so averaging it in only inflates the score. The {@code 1.0} returned for
     * an empty ground truth is a defensive fallback, not a measurement.
     */
    public double recallAtK(List<VectorSearchResult> exact, List<VectorSearchResult> approximate, int k) {
        int denominator = Math.min(k, exact.size());
        if (denominator == 0) return 1.0;
        Set<String> expectedIds = new HashSet<>();
        exact.stream().limit(k).map(VectorSearchResult::id).forEach(expectedIds::add);
        long matches = approximate.stream().limit(k).map(VectorSearchResult::id).filter(expectedIds::contains).distinct().count();
        return (double) matches / denominator;
    }

    /** True when the query has an exact top-K to reproduce, so Recall@K is meaningful. */
    public boolean hasGroundTruth(List<VectorSearchResult> exact) {
        return exact != null && !exact.isEmpty();
    }

    /**
     * Pass condition for a query whose filter matches no document: the store must return nothing.
     * Returning any row means the filter let through a document the exact search excluded.
     */
    public boolean returnedNothing(List<VectorSearchResult> approximate) {
        return approximate == null || approximate.isEmpty();
    }
}
