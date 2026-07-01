package com.meeting.controller;

import com.meeting.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "false") boolean kbOnly) {

        var results = searchService.search(query, 20, kbOnly);
        return ResponseEntity.ok(Map.of(
                "query", query,
                "results", results
        ));
    }
}
