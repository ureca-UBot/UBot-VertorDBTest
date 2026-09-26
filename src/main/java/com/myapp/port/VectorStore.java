package com.myapp.port;

import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.domain.vector.DistanceMetric;

import java.util.List;

/** Database-neutral contract used by application and benchmark code. */
public interface VectorStore {
    String database();
    int dimension();
    DistanceMetric metric();
    void upsert(List<VectorDocument> documents);
    List<VectorSearchResult> search(VectorSearchRequest request);
    void deleteAll();
    long count();
}
