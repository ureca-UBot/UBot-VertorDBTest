package com.myapp.infrastructure.rdb.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("document_chunks")
public record DocumentChunkEntity(@Id String id, String documentId, int sequence, String content, String metadataJson) {
}
