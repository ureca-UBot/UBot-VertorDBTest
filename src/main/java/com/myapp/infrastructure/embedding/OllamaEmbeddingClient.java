package com.myapp.infrastructure.embedding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minimal batch client for Ollama's local embedding API. */
public class OllamaEmbeddingClient {
    private final URI baseUri;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaEmbeddingClient(String baseUrl, String model, ObjectMapper objectMapper) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        JsonNode response = send("api/embed", Map.of(
                "model", model,
                "input", texts,
                "keep_alive", "30m"
        ));
        JsonNode embeddings = response.get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new IllegalStateException("Ollama returned an unexpected embedding count: " + response);
        }
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (JsonNode embedding : embeddings) {
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).asFloat();
                if (!Float.isFinite(vector[i])) throw new IllegalStateException("Ollama returned a non-finite vector value");
            }
            vectors.add(vector);
        }
        return List.copyOf(vectors);
    }

    public String modelDigest() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        JsonNode response = send(request);
        JsonNode models = response.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode item : models) {
                if (model.equals(item.get("name").asString()) || model.equals(item.get("model").asString())) {
                    JsonNode digest = item.get("digest");
                    return digest == null ? "unavailable" : digest.asString();
                }
            }
        }
        return "unavailable";
    }

    private JsonNode send(String path, Object body) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                }
                lastFailure = new IllegalStateException(
                        "Ollama request failed with HTTP " + response.statusCode() + ": " + response.body());
                if (!isTransient(response.statusCode(), response.body()) || attempt == 5) throw lastFailure;
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("Cannot call Ollama at " + baseUri, exception);
                if (attempt == 5) throw lastFailure;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ollama embedding request was interrupted", exception);
            }
            waitBeforeRetry(attempt);
        }
        throw lastFailure == null ? new IllegalStateException("Ollama request failed") : lastFailure;
    }

    private boolean isTransient(int statusCode, String body) {
        String message = body.toLowerCase();
        return statusCode == 408 || statusCode == 429 || statusCode >= 500
                || (statusCode == 400 && (message.contains("connect")
                || message.contains("connection") || message.contains("refused") || message.contains("loading model")));
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(8_000L, 1_000L << (attempt - 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Ollama", exception);
        }
    }
}
