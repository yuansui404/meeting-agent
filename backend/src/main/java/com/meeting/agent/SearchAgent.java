package com.meeting.agent;

import com.meeting.service.DialogueService;
import com.meeting.service.SearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 搜索 Agent
 * 负责全文检索、向量检索和混合搜索
 */
@Component
public class SearchAgent {

    private final SearchService searchService;
    private final DialogueService dialogueService;

    public SearchAgent(SearchService searchService, DialogueService dialogueService) {
        this.searchService = searchService;
        this.dialogueService = dialogueService;
    }

    public List<Map<String, Object>> search(String query, Long dialogueId) {
        if (dialogueId != null) {
            dialogueService.addMessage(dialogueId, "user", query, "search");
        }
        return searchService.search(query, 20);
    }
}
