package com.meeting.controller;

import com.meeting.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;
    private final com.meeting.service.DialogueService dialogueService;

    public SearchController(SearchService searchService,
                            com.meeting.service.DialogueService dialogueService) {
        this.searchService = searchService;
        this.dialogueService = dialogueService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(required = false) Long dialogueId) {

        // 如果有对话上下文，记录搜索消息
        if (dialogueId != null) {
            dialogueService.addMessage(dialogueId, "user", query, "search");
        }

        var results = searchService.search(query, 20);
        return ResponseEntity.ok(Map.of(
                "query", query,
                "results", results
        ));
    }
}
