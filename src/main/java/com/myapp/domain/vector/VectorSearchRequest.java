package com.myapp.domain.vector;

import java.util.Map;

public record VectorSearchRequest(
        float[] queryVector,
        int topK,
        VectorFilter filter,
        Map<String, Object> searchParameters
) {
    public VectorSearchRequest {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        queryVector = queryVector.clone();
        filter = filter == null ? VectorFilter.NONE : filter;
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
    }

    public VectorSearchRequest(float[] queryVector, int topK, VectorFilter filter) {
        this(queryVector, topK, filter, Map.of());
    }

    @Override
    public float[] queryVector() {
        return queryVector.clone();
    }

    public int intParameter(String name, int fallback) {
        Object value = searchParameters.get(name);
        if (value instanceof Number number) return number.intValue();
        return value == null ? fallback : Integer.parseInt(value.toString());
    }
}
