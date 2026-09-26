package com.myapp.application.search;

import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.port.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public VectorSearchService(ObjectProvider<VectorStore> vectorStoreProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
    }

    public List<VectorSearchResult> search(VectorSearchRequest request) {
        return requiredStore().search(request);
    }

    public String database() {
        return requiredStore().database();
    }

    private VectorStore requiredStore() {
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (store == null) throw new IllegalStateException("No vector store is active. Enable a vector DB Spring profile.");
        return store;
    }
}
