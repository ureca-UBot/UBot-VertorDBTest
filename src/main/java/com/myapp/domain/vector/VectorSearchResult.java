package com.myapp.domain.vector;

public record VectorSearchResult(String id, String documentId, String chunkId, double score) {
}
