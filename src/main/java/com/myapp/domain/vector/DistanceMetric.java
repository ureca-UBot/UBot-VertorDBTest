package com.myapp.domain.vector;

public enum DistanceMetric {
    COSINE,
    DOT,
    EUCLIDEAN;

    public static DistanceMetric from(String value) {
        return value == null ? COSINE : valueOf(value.trim().toUpperCase());
    }
}
