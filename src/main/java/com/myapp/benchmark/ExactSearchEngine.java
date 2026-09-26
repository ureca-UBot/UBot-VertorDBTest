package com.myapp.benchmark;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorFilter;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.LinkedHashSet;

public class ExactSearchEngine {
    private static final Comparator<VectorSearchResult> BEST_FIRST = Comparator
            .comparingDouble(VectorSearchResult::score).reversed()
            .thenComparing(VectorSearchResult::id);
    private final List<IndexedDocument> documents;
    private final DistanceMetric metric;

    public ExactSearchEngine(List<VectorDocument> documents, DistanceMetric metric) {
        this.documents = documents.stream().map(document -> {
            float[] embedding = document.embedding();
            return new IndexedDocument(document, embedding, norm(embedding));
        }).toList();
        this.metric = metric;
    }

    public List<VectorSearchResult> search(VectorSearchRequest request) {
        PriorityQueue<VectorSearchResult> top = new PriorityQueue<>(request.topK(), BEST_FIRST.reversed());
        float[] queryVector = request.queryVector();
        double queryNorm = norm(queryVector);
        for (IndexedDocument indexed : documents) {
            VectorDocument document = indexed.document();
            if (!matches(document, request.filter())) continue;
            VectorSearchResult candidate = new VectorSearchResult(
                    document.id(), document.documentId(), document.chunkId(),
                    similarity(queryVector, queryNorm, indexed.embedding(), indexed.norm()));
            if (top.size() < request.topK()) {
                top.add(candidate);
            } else if (BEST_FIRST.compare(candidate, top.peek()) < 0) {
                top.poll();
                top.add(candidate);
            }
        }
        return top.stream().sorted(BEST_FIRST).toList();
    }

    public ExactGroundTruth groundTruth(VectorSearchRequest request, double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0) {
            throw new IllegalArgumentException("Tie tolerance must be finite and nonnegative");
        }
        List<VectorSearchResult> strict = search(request);
        if (strict.isEmpty()) return new ExactGroundTruth(strict, java.util.Set.of(), java.util.Set.of(), null, tolerance);
        double boundary = strict.getLast().score();
        var better = new LinkedHashSet<String>();
        var tied = new LinkedHashSet<String>();
        float[] query = request.queryVector();
        double queryNorm = norm(query);
        // A second exact pass includes the ENTIRE boundary group, not merely K+1 neighbors.
        for (IndexedDocument indexed : documents) {
            if (!matches(indexed.document(), request.filter())) continue;
            double score = similarity(query, queryNorm, indexed.embedding(), indexed.norm());
            if (score > boundary + tolerance) better.add(indexed.document().id());
            else if (Math.abs(score - boundary) <= tolerance) tied.add(indexed.document().id());
        }
        return new ExactGroundTruth(strict, better, tied, boundary, tolerance);
    }

    private boolean matches(VectorDocument document, VectorFilter filter) {
        return filter.matches(document.metadata());
    }

    private double similarity(float[] left, double leftNorm, float[] right, double rightNorm) {
        if (left.length != right.length) throw new IllegalArgumentException("Vector dimensions do not match");
        return switch (metric) {
            case COSINE -> cosine(left, leftNorm, right, rightNorm);
            case DOT -> dot(left, right);
            case EUCLIDEAN -> -euclidean(left, right);
        };
    }

    private double cosine(float[] left, double leftNorm, float[] right, double rightNorm) {
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot(left, right) / (leftNorm * rightNorm);
    }

    private double dot(float[] left, float[] right) {
        double result = 0;
        for (int i = 0; i < left.length; i++) result += left[i] * right[i];
        return result;
    }

    private double euclidean(float[] left, float[] right) {
        double result = 0;
        for (int i = 0; i < left.length; i++) {
            double difference = left[i] - right[i];
            result += difference * difference;
        }
        return Math.sqrt(result);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        return Math.sqrt(sum);
    }

    private record IndexedDocument(VectorDocument document, float[] embedding, double norm) {
    }
}
