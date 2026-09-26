package com.myapp.benchmark;

import com.myapp.domain.vector.VectorSearchResult;
import java.util.List;
import java.util.Set;

/** Boundary ties are computed from source vectors, never from an adapter's rounded scores. */
public record ExactGroundTruth(List<VectorSearchResult> strictTopK, Set<String> strictlyBetterIds,
                               Set<String> boundaryIds, Double boundaryScore, double tieTolerance) {
    public ExactGroundTruth {
        strictTopK = List.copyOf(strictTopK);
        strictlyBetterIds = Set.copyOf(strictlyBetterIds);
        boundaryIds = Set.copyOf(boundaryIds);
    }
}
