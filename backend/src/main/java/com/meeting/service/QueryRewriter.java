package com.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meeting.config.DeepSeekChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final DeepSeekChatClient deepSeekChatClient;

    public QueryRewriter(DeepSeekChatClient deepSeekChatClient) {
        this.deepSeekChatClient = deepSeekChatClient;
    }

    /**
     * Extract search keywords from the user's natural language query.
     * Uses LLM for intelligent extraction, falls back to simple text splitting.
     */
    public List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return List.of();

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个搜索关键词提取助手。从用户的查询中提取搜索关键词（人名、日期、主题、术语等）。"
                            + "返回逗号分隔的关键词列表，不要其他任何内容。"
                            + "示例：\n"
                            + "用户：高玉坤参加过哪些会议\n"
                            + "输出：高玉坤,会议\n"
                            + "用户：内存激励方案的具体内容是什么\n"
                            + "输出：内存激励方案\n"
                            + "用户：帮我查一下上周的工业互联网会议\n"
                            + "输出：工业互联网,会议"),
                    Map.of("role", "user", "content", query)
            );

            JsonNode response = deepSeekChatClient.chat(messages, false);
            String extracted = extractContentText(response);
            if (extracted == null || extracted.isBlank()) {
                return fallbackSplit(query);
            }

            String[] parts = extracted.split("[，,、]");
            List<String> keywords = new ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    keywords.add(trimmed);
                }
            }
            return keywords.isEmpty() ? fallbackSplit(query) : keywords;
        } catch (Exception e) {
            log.warn("QueryRewriter LLM extraction failed, using fallback: {}", e.getMessage());
            return fallbackSplit(query);
        }
    }

    private String extractContentText(JsonNode response) {
        try {
            return response.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fallback: remove common question patterns and split by punctuation.
     */
    private List<String> fallbackSplit(String query) {
        String cleaned = query
                .replaceAll("(?:参加过哪些|有哪些|是什么|有哪些|帮我|查一下|查查|找一下|搜索)", "")
                .trim();
        if (cleaned.isBlank()) cleaned = query;

        String[] parts = cleaned.split("[，,、\\s]+");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.length() >= 2) result.add(t);
        }
        return result.isEmpty() ? List.of(cleaned) : result;
    }
}
