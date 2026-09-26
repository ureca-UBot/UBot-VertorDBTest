package com.myapp.infrastructure.vector.weaviate;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "weaviate")
@EnableConfigurationProperties(WeaviateProperties.class)
public class WeaviateConfig {
    @Bean
    JsonHttpClient weaviateHttpClient(WeaviateProperties properties, ObjectMapper objectMapper) {
        Map<String, String> headers = properties.getApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getApiKey());
        return new JsonHttpClient(properties.getBaseUrl(), headers, objectMapper);
    }

    @Bean
    WeaviateVectorStore weaviateVectorStore(JsonHttpClient client, WeaviateProperties properties, ObjectMapper objectMapper) {
        return new WeaviateVectorStore(client, properties, objectMapper);
    }

    @Bean
    WeaviateIndexManager weaviateIndexManager(JsonHttpClient client, WeaviateProperties properties) {
        return new WeaviateIndexManager(client, properties);
    }
}
