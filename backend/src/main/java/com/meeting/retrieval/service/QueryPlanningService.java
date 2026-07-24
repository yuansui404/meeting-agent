package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.RetryUtils;
import com.meeting.config.RagProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryPlanningService {

    private static final String SYSTEM_PROMPT =
            "你是一个查询规划器。分析用户问题，输出 JSON 格式的规划结果。\n" +
            "- DIRECT: 原样检索\n" +
            "- REWRITE: 改写 query 使其更适合检索\n" +
            "- DECOMPOSE: 拆解为多个子问题分别检索\n\n" +
            "同时分析问题的时间意图 time_intent：\n" +
            "- RECENT: 隐含问近期（最近/本周/本月/刚刚/最近一次）\n" +
            "- HISTORICAL: 明确问历史（去年/以往/历史/202X年/之前/过去）\n" +
            "- NEUTRAL: 无时间偏好的事实性问题（什么是/如何/方法）\n" +
            "- RANGE: 指定了具体时间段（需要同时提取 time_range 描述）\n\n" +
            "输出格式: {\"strategy\": \"DIRECT|REWRITE|DECOMPOSE\", " +
            "\"rewritten_query\": \"...\", \"sub_queries\": [\"...\"], " +
            "\"time_intent\": \"RECENT|HISTORICAL|NEUTRAL|RANGE\", \"time_range\": \"...\"}";

    private final OpenAIChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public QueryPlanningService(@Qualifier("nonStreamingOpenAIChatModel") OpenAIChatModel chatModel,
                                ObjectMapper objectMapper, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    public enum TimeIntent {
        RECENT, HISTORICAL, NEUTRAL, RANGE
    }

    public record QueryPlan(
            String strategy,
            String rewrittenQuery,
            List<String> subQueries,
            TimeIntent timeIntent,
            String timeRange
    ) {
        public static QueryPlan direct(String query) {
            return new QueryPlan("DIRECT", query, List.of(query), TimeIntent.RECENT, null);
        }
    }

    public QueryPlan plan(String originalQuery) {
        try {
            List<Msg> messages = List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(originalQuery)
            );

            RagProperties.Retry retryConfig = ragProperties.getRetry();
            ChatResponse response = RetryUtils.retryWithBackoff("QueryPlanning",
                    retryConfig.getMaxAttempts(), retryConfig.getInitialDelayMs(), () -> {
                        ChatResponse r = chatModel.stream(messages, null,
                                GenerateOptions.builder()
                                        .stream(false)
                                        .temperature(0.1)
                                        .maxTokens(2048)
                                        .build()
                        ).blockLast();
                        if (r == null) throw new RuntimeException("Query planning returned null response");
                        return r;
                    });

            String content = response.getContent().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining());

            JsonNode root = objectMapper.readTree(content);
            String strategy = root.has("strategy") ? root.get("strategy").asText("DIRECT") : "DIRECT";
            TimeIntent timeIntent = parseTimeIntent(root);
            String timeRange = root.has("time_range") ? root.get("time_range").asText("") : "";

            return switch (strategy) {
                case "REWRITE" -> {
                    String rewritten = root.has("rewritten_query")
                            ? root.get("rewritten_query").asText(originalQuery)
                            : originalQuery;
                    yield new QueryPlan("REWRITE", rewritten, List.of(rewritten),
                            timeIntent, timeRange);
                }
                case "DECOMPOSE" -> {
                    List<String> subQueries = parseSubQueries(root, originalQuery);
                    yield new QueryPlan("DECOMPOSE", originalQuery, subQueries,
                            timeIntent, timeRange);
                }
                default -> {
                    yield new QueryPlan("DIRECT", originalQuery, List.of(originalQuery),
                            timeIntent, timeRange);
                }
            };
        } catch (Exception e) {
            log.warn("Query planning failed, fallback to DIRECT: {}", e.getMessage());
            return QueryPlan.direct(originalQuery);
        }
    }

    private TimeIntent parseTimeIntent(JsonNode root) {
        if (!root.has("time_intent")) return TimeIntent.RECENT;
        String raw = root.get("time_intent").asText("RECENT");
        try {
            return TimeIntent.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TimeIntent.RECENT;
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
