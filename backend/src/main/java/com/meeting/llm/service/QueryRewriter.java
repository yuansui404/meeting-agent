package com.meeting.llm.service;

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
public class QueryRewriter {

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;

    /**
     * Extract search keywords from the user's natural language query.
     * Uses LLM for intelligent extraction, falls back to simple text splitting.
     */
    public List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return List.of();

        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepSeekProps.getModel())
                    .messages(List.of(
                            OpenAIMessage.builder().role("system").content(
                                    "你是一个搜索关键词提取助手。从用户的查询中提取搜索关键词（人名、日期、主题、术语等）。"
                                    + "返回逗号分隔的关键词列表，不要其他任何内容。"
                                    + "示例：\n"
                                    + "用户：高玉坤参加过哪些会议\n"
                                    + "输出：高玉坤,会议\n"
                                    + "用户：内存激励方案的具体内容是什么\n"
                                    + "输出：内存激励方案\n"
                                    + "用户：帮我查一下上周的工业互联网会议\n"
                                    + "输出：工业互联网,会议").build(),
                            OpenAIMessage.builder().role("user").content(query).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(256)
                    .build();

            OpenAIResponse response = openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request);
            String extracted = response.getFirstChoice().getMessage().getContentAsString();
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

    /**
     * Fallback: remove common question patterns and split by punctuation.
     */
    private List<String> fallbackSplit(String query) {
        String cleaned = query
                .replaceAll("(?:参加过哪些|有哪些|是什么|帮我|查一下|查查|找一下|搜索)", "")
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
