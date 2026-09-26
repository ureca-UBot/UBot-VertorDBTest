package com.myapp.infrastructure.rdb.postgres.repository;

import com.myapp.infrastructure.rdb.postgres.entity.DocumentChunkEntity;
import org.springframework.data.repository.CrudRepository;

public interface DocumentChunkRepository extends CrudRepository<DocumentChunkEntity, String> {
    void deleteByDocumentId(String documentId);
}
