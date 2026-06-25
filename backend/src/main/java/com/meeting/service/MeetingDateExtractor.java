package com.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meeting.config.DeepSeekChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class MeetingDateExtractor {

    private static final Logger log = LoggerFactory.getLogger(MeetingDateExtractor.class);
    private static final DateTimeFormatter[] FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    private final DeepSeekChatClient deepSeekChatClient;

    public MeetingDateExtractor(DeepSeekChatClient deepSeekChatClient) {
        this.deepSeekChatClient = deepSeekChatClient;
    }

    /**
     * Extract meeting date from document/transcription text using LLM.
     * Returns null if no date can be extracted.
     */
    public LocalDateTime extract(String text) {
        if (text == null || text.isBlank()) return null;

        // Only use first 2000 chars for extraction (enough for date header)
        String sample = text.length() > 2000 ? text.substring(0, 2000) : text;

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个会议日期提取器。从以下会议纪要文本中提取会议日期。"
                                    + "如果内容中有明确的会议日期或时间，返回ISO格式（如2026-03-05T10:00:00）。"
                                    + "如果只有日期没有时间，使用10:00:00作为默认时间。"
                                    + "如果没有任何日期信息，返回null。"
                                    + "只返回日期字符串或null，不要其他内容。"),
                    Map.of("role", "user", "content", sample)
            );

            JsonNode response = deepSeekChatClient.chat(messages, false);
            String content = extractContent(response);

            if (content == null || content.isBlank() || "null".equals(content.trim())) {
                return null;
            }

            return parseDateTime(content.trim());
        } catch (Exception e) {
            log.warn("Failed to extract meeting date: {}", e.getMessage());
            return null;
        }
    }

    private String extractContent(JsonNode response) {
        try {
            return response.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String str) {
        // Remove quotes if present
        str = str.replace("\"", "").replace("'", "").trim();

        for (DateTimeFormatter fmt : FORMATTERS) {
            try {
                return LocalDateTime.parse(str, fmt);
            } catch (Exception ignored) {
            }
            // Try parsing as date only (LocalDate) then convert
            if (str.length() <= 10) {
                try {
                    java.time.LocalDate date = java.time.LocalDate.parse(str, fmt);
                    return date.atTime(10, 0);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
