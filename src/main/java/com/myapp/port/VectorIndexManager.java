package com.myapp.port;

import com.myapp.domain.vector.VectorDocument;

import java.util.Map;
import java.time.Duration;
import java.util.List;

public interface VectorIndexManager {
    String indexType();

    default String engine() {
        return "Native";
    }

    /** Returns the one recall-control parameter varied by the benchmark, or null for a fixed search. */
    default String searchParameterName() {
        return null;
    }

    default int minimumSearchParameter(int topK) {
        return topK;
    }

    default int maximumSearchParameter() {
        return Integer.MAX_VALUE;
    }

    void create();
    void drop();

    default void rebuild() {
        drop();
        create();
    }

    /** Training-based indexes can override this lifecycle while ordinary indexes use rebuild(). */
    default void rebuild(List<VectorDocument> trainingDocuments) {
        rebuild();
    }

    default long indexSizeBytes() {
        return -1L;
    }

    default Map<String, Object> indexParameters() {
        return Map.of();
    }

    default void configureSearch(Map<String, Object> searchParameters) {
        // Most stores accept search parameters per request. Stores with index-level settings override this.
    }

    default void awaitReady(long expectedVectorCount, Duration timeout) {
        // Synchronous stores need no additional readiness barrier.
    }

    /** Stores with a known recall-drift risk can require the serial/concurrent audit. */
    default boolean requiresStabilityCheck() {
        return false;
    }

    /** Captured outside the timed workload. Values must be JSON-serializable. */
    default Map<String, Object> diagnostics() {
        return Map.of();
    }
}
