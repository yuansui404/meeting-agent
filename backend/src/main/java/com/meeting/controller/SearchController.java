package com.meeting.controller;

import com.meeting.retrieval.service.HybridSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SearchController {

    private final HybridSearchService hybridSearchService;

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(required = false) String timeRange) {

        var result = hybridSearchService.search(query, timeRange);
        return ResponseEntity.ok(Map.of(
                "query", query,
                "evidenceLevel", result.evidenceLevel(),
                "results", result.chunks(),
                "citations", result.citations()
        ));
    }
}
