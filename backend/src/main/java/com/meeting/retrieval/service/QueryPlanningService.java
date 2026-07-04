package com.meeting.retrieval.service;

import com.meeting.config.DeepSeekProperties;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;

    public record QueryPlan(String strategy, String rewrittenQuery, List<String> subQueries) {
        public static QueryPlan direct(String query) {
            return new QueryPlan("DIRECT", query, List.of(query));
        }
    }

    public QueryPlan plan(String originalQuery) {
        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepSeekProps.getModel())
                    .messages(List.of(
                            OpenAIMessage.builder().role("system").content(
                                    "你是一个查询规划器。分析用户问题，输出 JSON 格式的规划结果。\n" +
                                    "- DIRECT: 原样检索\n" +
                                    "- REWRITE: 改写 query 使其更适合检索\n" +
                                    "- DECOMPOSE: 拆解为多个子问题分别检索\n\n" +
                                    "输出格式: {\"strategy\": \"DIRECT|REWRITE|DECOMPOSE\", " +
                                    "\"rewritten_query\": \"...\", \"sub_queries\": [\"...\"]}").build(),
                            OpenAIMessage.builder().role("user").content(originalQuery).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(2048)
                    .build();

            OpenAIResponse response = openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request);
            String content = response.getFirstChoice().getMessage().getContentAsString();

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
