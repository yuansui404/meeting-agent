package com.meeting.agent;

import com.meeting.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 搜索 Agent
 * 负责全文检索、向量检索和混合搜索
 */
@Component
@RequiredArgsConstructor
public class SearchAgent {

    private final SearchService searchService;

    public List<Map<String, Object>> search(String query) {
        return searchService.search(query, 20);
    }
}
