package com.myapp.infrastructure.vector.pgvector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "pgvector")
@EnableConfigurationProperties(PgVectorProperties.class)
public class PgVectorConfig {
    @Bean
    PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PgVectorProperties properties) {
        return new PgVectorStore(jdbcTemplate, objectMapper, properties);
    }

    @Bean
    PgVectorIndexManager pgVectorIndexManager(JdbcTemplate jdbcTemplate, PgVectorProperties properties) {
        return new PgVectorIndexManager(jdbcTemplate, properties);
    }
}
