package com.myapp.api;

import com.myapp.application.search.VectorSearchService;
import com.myapp.domain.vector.VectorFilter;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final VectorSearchService searchService;

    public SearchController(VectorSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/store")
    public Map<String, String> activeStore() {
        return Map.of("database", searchService.database());
    }

    @PostMapping
    public ResponseEntity<SearchResponse> search(@RequestBody SearchBody body) {
        int topK = body.topK() == null ? 10 : body.topK();
        List<VectorSearchResult> results = searchService.search(new VectorSearchRequest(
                body.vector(), topK, new VectorFilter(body.filter()), body.searchParameters()));
        return ResponseEntity.ok(new SearchResponse(searchService.database(), results));
    }

    public record SearchBody(float[] vector, Integer topK, Map<String, Object> filter, Map<String, Object> searchParameters) {
    }

    public record SearchResponse(String database, List<VectorSearchResult> results) {
    }
}
