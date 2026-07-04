package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.config.DeepSeekProperties;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;
    private final ObjectMapper objectMapper;

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

            JsonNode root = objectMapper.readTree(content);
            String strategy = root.has("strategy") ? root.get("strategy").asText("DIRECT") : "DIRECT";

            switch (strategy) {
                case "REWRITE" -> {
                    String rewritten = root.has("rewritten_query") ? root.get("rewritten_query").asText(originalQuery) : originalQuery;
                    return new QueryPlan("REWRITE", rewritten, List.of(rewritten));
                }
                case "DECOMPOSE" -> {
                    List<String> subQueries = parseSubQueries(root, originalQuery);
                    return new QueryPlan("DECOMPOSE", originalQuery, subQueries);
                }
                default -> {
                    return QueryPlan.direct(originalQuery);
                }
            }
        } catch (Exception e) {
            log.warn("Query planning failed, fallback to DIRECT: {}", e.getMessage());
            return QueryPlan.direct(originalQuery);
        }
    }

    private List<String> parseSubQueries(JsonNode root, String fallback) {
        List<String> result = new ArrayList<>();
        if (root.has("sub_queries") && root.get("sub_queries").isArray()) {
            for (JsonNode node : root.get("sub_queries")) {
                String q = node.asText("").trim();
                if (!q.isBlank()) result.add(q);
            }
        }
        return result.isEmpty() ? List.of(fallback) : result;
    }
}
