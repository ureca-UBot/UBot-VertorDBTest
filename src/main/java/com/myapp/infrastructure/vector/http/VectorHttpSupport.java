package com.myapp.infrastructure.vector.http;

import com.myapp.domain.vector.VectorDocument;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VectorHttpSupport {
    private VectorHttpSupport() {
    }

    public static List<Float> floats(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return values;
    }

    public static String uuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public static void validateDimensions(List<VectorDocument> documents, int dimension) {
        if (documents.stream().anyMatch(document -> document.embedding().length != dimension)) {
            throw new IllegalArgumentException("Unexpected document vector dimension; expected " + dimension);
        }
    }
}
