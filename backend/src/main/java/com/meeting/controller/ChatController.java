package com.meeting.controller;

import com.meeting.controller.dto.request.ChatRequest;
import com.meeting.conversation.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/dialogue/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时
        chatService.streamChat(emitter, id, request.message(), request.files());
        return emitter;
    }
}
