package com.myapp.benchmark;

import com.myapp.domain.vector.*;
import java.util.*;

/** Validate identity, uniqueness and exact filter semantics outside the search timer. */
final class ResponseContract {
    private final Map<String, VectorDocument> documents = new HashMap<>();

    ResponseContract(List<VectorDocument> source) {
        for (VectorDocument document : source) {
            if (documents.putIfAbsent(document.id(), document) != null) {
                throw new IllegalArgumentException("Duplicate document ID: " + document.id());
            }
        }
    }

    List<String> violations(BenchmarkQuery query, List<VectorSearchResult> results, int k) {
        List<String> errors = new ArrayList<>();
        if (results == null) return List.of("null response");
        if (results.size() > k) errors.add("more than topK results");
        Set<String> seen = new HashSet<>();
        for (VectorSearchResult result : results) {
            if (result == null) { errors.add("null result"); continue; }
            if (!seen.add(result.id())) errors.add("duplicate ID: " + result.id());
            VectorDocument doc = documents.get(result.id());
            if (doc == null) { errors.add("unknown ID: " + result.id()); continue; }
            if (!doc.documentId().equals(result.documentId()) || !doc.chunkId().equals(result.chunkId())) {
                errors.add("identity mismatch: " + result.id());
            }
            if (!Double.isFinite(result.score())) errors.add("nonfinite score: " + result.id());
            if (!query.filter().matches(doc.metadata())) {
                errors.add("filter mismatch: " + result.id());
            }
        }
        return List.copyOf(errors);
    }
}
