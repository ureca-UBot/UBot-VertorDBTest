package com.myapp.application.document;

import com.myapp.domain.document.Document;
import com.myapp.domain.document.DocumentChunk;

import java.util.ArrayList;
import java.util.List;

public class ChunkingService {
    public List<DocumentChunk> chunk(Document document, int chunkSize, int overlap) {
        if (chunkSize < 1) throw new IllegalArgumentException("chunkSize must be positive");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        if (document.content().isEmpty()) return List.of();

        List<DocumentChunk> chunks = new ArrayList<>();
        int sequence = 0;
        for (int start = 0; start < document.content().length(); start += chunkSize - overlap) {
            int end = Math.min(start + chunkSize, document.content().length());
            chunks.add(new DocumentChunk(
                    document.id() + ":" + sequence,
                    document.id(),
                    sequence++,
                    document.content().substring(start, end),
                    document.metadata()
            ));
            if (end == document.content().length()) break;
        }
        return List.copyOf(chunks);
    }
}
