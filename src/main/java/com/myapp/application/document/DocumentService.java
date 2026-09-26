package com.myapp.application.document;

import com.myapp.domain.document.Document;
import com.myapp.domain.document.DocumentChunk;
import com.myapp.infrastructure.rdb.postgres.entity.DocumentChunkEntity;
import com.myapp.infrastructure.rdb.postgres.entity.DocumentEntity;
import com.myapp.infrastructure.rdb.postgres.repository.DocumentChunkRepository;
import com.myapp.infrastructure.rdb.postgres.repository.DocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "spring.data.jdbc.repositories", name = "enabled", havingValue = "true")
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository documentRepository, DocumentChunkRepository chunkRepository, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void replace(Document document, List<DocumentChunk> chunks) {
        documentRepository.save(new DocumentEntity(document.id(), document.title(), document.content(),
                objectMapper.writeValueAsString(document.metadata()), Instant.now()));
        chunkRepository.deleteByDocumentId(document.id());
        chunkRepository.saveAll(chunks.stream().map(chunk -> new DocumentChunkEntity(
                chunk.id(), chunk.documentId(), chunk.sequence(), chunk.content(), objectMapper.writeValueAsString(chunk.metadata()))).toList());
    }
}
