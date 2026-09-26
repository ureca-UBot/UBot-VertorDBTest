package com.myapp.dataset;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class JsonlSupport {
    private JsonlSupport() {
    }

    static List<JsonNode> read(Path path, ObjectMapper mapper) {
        return readMapped(path, mapper, Function.identity());
    }

    static <T> List<T> readMapped(Path path, ObjectMapper mapper, Function<JsonNode, T> converter) {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("JSONL file not found: " + path);
        List<T> values = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    values.add(converter.apply(mapper.readTree(line)));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid JSON at " + path + ":" + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
        return values;
    }

    static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asString().isBlank()) return value.asString();
        }
        return null;
    }

    static float[] vector(JsonNode node, String... names) {
        JsonNode array = null;
        for (String name : names) {
            if (node.get(name) != null && node.get(name).isArray()) {
                array = node.get(name);
                break;
            }
        }
        if (array == null || array.isEmpty()) throw new IllegalArgumentException("Missing vector field");
        float[] vector = new float[array.size()];
        for (int index = 0; index < array.size(); index++) {
            JsonNode value = array.get(index);
            if (!value.isNumber()) throw new IllegalArgumentException("Vector values must be numbers at index " + index);
            vector[index] = (float) value.asDouble();
            if (!Float.isFinite(vector[index])) throw new IllegalArgumentException("Vector values must be finite at index " + index);
        }
        return vector;
    }

    static Map<String, Object> object(JsonNode node, ObjectMapper mapper, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return Map.of();
        if (!value.isObject()) throw new IllegalArgumentException(name + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            result.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
        }
        return result;
    }
}
