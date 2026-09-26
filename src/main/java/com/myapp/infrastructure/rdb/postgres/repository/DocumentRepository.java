package com.myapp.infrastructure.rdb.postgres.repository;

import com.myapp.infrastructure.rdb.postgres.entity.DocumentEntity;
import org.springframework.data.repository.CrudRepository;

public interface DocumentRepository extends CrudRepository<DocumentEntity, String> {
}
