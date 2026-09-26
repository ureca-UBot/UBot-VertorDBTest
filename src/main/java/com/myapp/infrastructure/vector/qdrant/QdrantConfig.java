package com.myapp.infrastructure.vector.qdrant;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "qdrant")
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {
    @Bean
    JsonHttpClient qdrantHttpClient(QdrantProperties properties, ObjectMapper objectMapper) {
        Map<String, String> headers = properties.getApiKey().isBlank() ? Map.of() : Map.of("api-key", properties.getApiKey());
        return new JsonHttpClient(properties.getBaseUrl(), headers, objectMapper);
    }

    @Bean
    QdrantVectorStore qdrantVectorStore(JsonHttpClient qdrantHttpClient, QdrantProperties properties) {
        return new QdrantVectorStore(qdrantHttpClient, properties);
    }

    @Bean
    QdrantIndexManager qdrantIndexManager(JsonHttpClient qdrantHttpClient, QdrantProperties properties) {
        return new QdrantIndexManager(qdrantHttpClient, properties);
    }
}
