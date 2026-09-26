package com.myapp.infrastructure.vector.http;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonHttpClient {
    private final URI baseUri;
    private final Map<String, String> defaultHeaders;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public JsonHttpClient(String baseUrl, Map<String, String> defaultHeaders, ObjectMapper objectMapper) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.defaultHeaders = Map.copyOf(defaultHeaders);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public JsonNode get(String path) {
        return send("GET", path, null, "application/json");
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, objectMapper.writeValueAsString(body), "application/json");
    }

    public JsonNode put(String path, Object body) {
        return send("PUT", path, objectMapper.writeValueAsString(body), "application/json");
    }

    public JsonNode delete(String path) {
        return send("DELETE", path, null, "application/json");
    }

    public JsonNode sendRaw(String method, String path, String body, String contentType) {
        return send(method, path, body, contentType);
    }

    private JsonNode send(String method, String path, String body, String contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofMinutes(2));
        Map<String, String> headers = new LinkedHashMap<>(defaultHeaders);
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", contentType);
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new VectorStoreHttpException(response.statusCode(), method + " " + path + " failed: " + response.body());
            }
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Vector DB request failed: " + method + " " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vector DB request interrupted", exception);
        }
    }
}
