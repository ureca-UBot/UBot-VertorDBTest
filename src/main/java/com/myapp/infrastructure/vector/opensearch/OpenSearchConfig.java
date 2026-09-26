package com.myapp.infrastructure.vector.opensearch;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "opensearch")
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchConfig {
    @Bean
    JsonHttpClient openSearchHttpClient(OpenSearchProperties properties, ObjectMapper objectMapper) {
        Map<String, String> headers = properties.getUsername().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (properties.getUsername() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8)));
        return new JsonHttpClient(properties.getBaseUrl(), headers, objectMapper);
    }

    @Bean
    OpenSearchVectorStore openSearchVectorStore(JsonHttpClient client, OpenSearchProperties properties, ObjectMapper objectMapper) {
        return new OpenSearchVectorStore(client, properties, objectMapper);
    }

    @Bean
    OpenSearchIndexManager openSearchIndexManager(JsonHttpClient client, OpenSearchProperties properties, ObjectMapper objectMapper) {
        return new OpenSearchIndexManager(client, properties, objectMapper);
    }
}
