package com.meeting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.BusinessException;
import com.meeting.controller.dto.request.ChatRequest;
import com.meeting.service.CancellationToken;
import com.meeting.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/dialogue/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String metadata = buildMetadata(request);
        CancellationToken cancelToken = chatService.streamChat(id, request.message(), metadata, emitter);

        emitter.onTimeout(() -> {
            log.info("SSE timeout for dialogue {}", id);
            cancelToken.cancel();
        });
        emitter.onCompletion(() -> {
            log.debug("SSE completed for dialogue {}", id);
            cancelToken.cancel();
        });
        emitter.onError(e -> {
            log.info("SSE error for dialogue {}: {}", id, e.getMessage());
            cancelToken.cancel();
        });

        return emitter;
    }

    private String buildMetadata(ChatRequest request) {
        if (request.fileIds() == null && request.files() == null) return null;
        try {
            java.util.Map<String, Object> meta = new java.util.HashMap<>();
            if (request.fileIds() != null) meta.put("fileIds", request.fileIds());
            if (request.files() != null) meta.put("files", request.files());
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new BusinessException("请求参数序列化失败", e);
        }
    }
}
