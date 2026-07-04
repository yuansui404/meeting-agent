package com.meeting.service;

import com.meeting.config.DeepSeekProperties;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingDateExtractor {

    private static final DateTimeFormatter[] FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;

    /**
     * Extract meeting date from document/transcription text using LLM.
     * Returns null if no date can be extracted.
     */
    public LocalDateTime extract(String text) {
        if (text == null || text.isBlank()) return null;

        // Only use first 2000 chars for extraction (enough for date header)
        String sample = text.length() > 2000 ? text.substring(0, 2000) : text;

        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepSeekProps.getModel())
                    .messages(List.of(
                            OpenAIMessage.builder().role("system").content(
                                    "你是一个会议日期提取器。从以下会议纪要文本中提取会议日期。"
                                    + "如果内容中有明确的会议日期或时间，返回ISO格式（如2026-03-05T10:00:00）。"
                                    + "如果只有日期没有时间，使用10:00:00作为默认时间。"
                                    + "如果没有任何日期信息，返回null。"
                                    + "只返回日期字符串或null，不要其他内容。").build(),
                            OpenAIMessage.builder().role("user").content(sample).build()
                    ))
                    .temperature(0.0)
                    .maxTokens(64)
                    .build();

            OpenAIResponse response = openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request);
            String content = response.getFirstChoice().getMessage().getContentAsString();

            if (content == null || content.isBlank() || "null".equals(content.trim())) {
                return null;
            }

            return parseDateTime(content.trim());
        } catch (Exception e) {
            log.warn("Failed to extract meeting date: {}", e.getMessage());
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
