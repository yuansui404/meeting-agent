package com.meeting.controller;

import com.meeting.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/dialogue/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        SseEmitter emitter = new SseEmitter(300_000L);

        if (message == null || message.trim().isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("消息不能为空"));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        // All messages go to the main HarnessAgent (MeetingAssistant).
        // The agent decides whether to:
        // - use built-in tools (search_kb, list_meetings, etc.)
        // - spawn sub-agents (rewrite_agent, proofread_agent) via agentSpawn
        // - respond directly
        String metadata = buildMetadata(request);
        chatService.streamChat(id, message.trim(), metadata, emitter);
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private String buildMetadata(Map<String, Object> request) {
        StringBuilder sb = new StringBuilder("{");
        boolean hasContent = false;

        // Include fileIds (numeric, backward compat)
        if (request.containsKey("fileIds")) {
            Object fileIdsObj = request.get("fileIds");
            String json = objectToJson(fileIdsObj);
            if (json != null) {
                sb.append("\"fileIds\":").append(json);
                hasContent = true;
            }
        }

        // Include file metadata (new style: array of {fileId, fileName, filePath, ext})
        if (request.containsKey("files")) {
            Object filesObj = request.get("files");
            if (filesObj instanceof List) {
                try {
                    String filesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(filesObj);
                    if (hasContent) sb.append(",");
                    sb.append("\"files\":").append(filesJson);
                    hasContent = true;
                } catch (Exception ignored) {}
            }
        }

        sb.append("}");
        return hasContent ? sb.toString() : null;
    }

    private String objectToJson(Object obj) {
        if (obj instanceof List list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var item : list) {
                if (!first) sb.append(",");
                first = false;
                if (item instanceof Number) sb.append(item.toString());
                else sb.append("\"").append(item.toString().replace("\"", "\\\"")).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }
        return "[]";
    }
}
