package com.meeting.controller;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.ChatService;
import com.meeting.service.RewriteService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Set<String> REWRITE_KEYWORDS = Set.of(
            "改写", "润色", "修稿", "重写", "润色改写", "重新写",
            "rewrite", "polish", "refine"
    );

    private final ChatService chatService;
    private final RewriteService rewriteService;
    private final MeetingMinutesRepository meetingRepository;

    public ChatController(ChatService chatService,
                          RewriteService rewriteService,
                          MeetingMinutesRepository meetingRepository) {
        this.chatService = chatService;
        this.rewriteService = rewriteService;
        this.meetingRepository = meetingRepository;
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

        // Check rewrite intent
        if (isRewriteIntent(message)) {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(id);
            if (!files.isEmpty()) {
                List<Long> sourceFileIds = parseSourceFiles(files, message);
                List<Long> manualRefIds = parseStyleReferences(message);
                rewriteService.streamRewrite(id, sourceFileIds, manualRefIds, emitter);
                return emitter;
            }
            // No files to rewrite — fall through to normal chat
        }

        // Build metadata JSON from optional fileIds
        String metadata = null;
        if (request.containsKey("fileIds")) {
            Object fileIdsObj = request.get("fileIds");
            try {
                String fileIdsJson = objectToJson(fileIdsObj);
                metadata = "{\"fileIds\":" + fileIdsJson + "}";
            } catch (Exception ignored) {}
        }

        chatService.streamChat(id, message.trim(), metadata, emitter);
        return emitter;
    }

    private boolean isRewriteIntent(String message) {
        String lower = message.toLowerCase();
        return REWRITE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private List<Long> parseSourceFiles(List<MeetingMinutes> files, String message) {
        // Default: all uploaded files in dialogue
        return files.stream().map(MeetingMinutes::getId).collect(Collectors.toList());
    }

    private List<Long> parseStyleReferences(String message) {
        // "参照XXX的风格" → extract document name, look up by title
        // For now, return empty to trigger automatic retrieval
        // Future enhancement: regex match "参照(.+?)的风格" and search meeting_minutes by title
        return List.of();
    }

    private String objectToJson(Object obj) {
        if (obj instanceof java.util.List list) {
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
