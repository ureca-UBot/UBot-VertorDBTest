package com.myapp.domain.vector;

public record BenchmarkQuery(
        String queryId,
        String query,
        String queryType,
        boolean synthetic,
        float[] embedding,
        VectorFilter filter
) {
    public BenchmarkQuery {
        if (queryId == null || queryId.isBlank()) throw new IllegalArgumentException("queryId must not be blank");
        query = query == null ? "" : query;
        queryType = queryType == null || queryType.isBlank() ? "unspecified" : queryType;
        if (embedding == null || embedding.length == 0) throw new IllegalArgumentException("embedding must not be empty");
        embedding = embedding.clone();
        filter = filter == null ? VectorFilter.NONE : filter;
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
