package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话入口 Facade。负责 metadata 解析和组件编排，具体职责委托给：
 * - FileContextBuilder：文件上下文构建
 * - ChatStreamService：SSE 流控制
 * - DialoguePersistenceService：消息持久化
 */
@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class ChatService {

    private final FileContextBuilder fileContextBuilder;
    private final ChatStreamService chatStreamService;
    @Qualifier("llmTaskExecutor") private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        taskExecutor.execute(() -> {
            try {
                List<Map<String, Object>> messageFiles = parseFilesFromMetadata(metadata);
                String fileContext = fileContextBuilder.build(messageFiles);
                String enriched = fileContextBuilder.buildEnrichedMessage(fileContext, userMessage);
                chatStreamService.stream(dialogueId, enriched, userMessage, messageFiles, emitter);
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private List<Map<String, Object>> parseFilesFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return List.of();
        try {
            Map<String, Object> map = objectMapper.readValue(metadata,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object files = map.get("files");
            if (files instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) item;
                        result.add(m);
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to parse files from metadata: {}", e.getMessage());
            return List.of();
        }
    }

    private void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            String msg = e instanceof RuntimeException && e.getCause() != null
                    ? e.getCause().getMessage()
                    : e.getMessage();
            emitter.send(SseEmitter.event().name("error").data(msg != null ? msg : "Unknown error"));
        } catch (Exception ignored) {
        }
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
        }
    }
}
