package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meeting.config.DeepSeekChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private final DeepSeekChatClient deepSeekChatClient;

    public record QueryPlan(String strategy, String rewrittenQuery, List<String> subQueries) {
        public static QueryPlan direct(String query) {
            return new QueryPlan("DIRECT", query, List.of(query));
        }
    }

    public QueryPlan plan(String originalQuery) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个查询规划器。分析用户问题，输出 JSON 格式的规划结果。\n" +
                            "- DIRECT: 原样检索\n" +
                            "- REWRITE: 改写 query 使其更适合检索\n" +
                            "- DECOMPOSE: 拆解为多个子问题分别检索\n\n" +
                            "输出格式: {\"strategy\": \"DIRECT|REWRITE|DECOMPOSE\", " +
                            "\"rewritten_query\": \"...\", \"sub_queries\": [\"...\"]}"),
                    Map.of("role", "user", "content", originalQuery)
            );

            JsonNode result = deepSeekChatClient.chat(messages, false);
            String content = result.path("choices").get(0)
                    .path("message").path("content").asText();

            if (content.contains("\"REWRITE\"")) {
                return new QueryPlan("REWRITE", extractFromJson(content, "rewritten_query"),
                        List.of(extractFromJson(content, "rewritten_query")));
            } else if (content.contains("\"DECOMPOSE\"")) {
                return new QueryPlan("DECOMPOSE", originalQuery, List.of(originalQuery));
            }
            return QueryPlan.direct(originalQuery);

        } catch (Exception e) {
            log.warn("Query planning failed, fallback to DIRECT: {}", e.getMessage());
            return QueryPlan.direct(originalQuery);
        }
    }

    private String extractFromJson(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        start = json.indexOf("\"", start) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
