package com.myapp.infrastructure.rdb.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("documents")
public record DocumentEntity(@Id String id, String title, String content, String metadataJson, Instant createdAt) {
}
