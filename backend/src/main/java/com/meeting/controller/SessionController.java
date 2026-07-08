package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.request.CreateSessionRequest;
import com.meeting.controller.dto.request.UpdateTitleRequest;
import com.meeting.conversation.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @PostMapping("/dialogue")
    public ApiResponse<Map<String, Object>> createSession(@Valid @RequestBody CreateSessionRequest request) {
        String title = request.title() != null ? request.title() : "新对话";
        var session = sessionService.createSession(title, request.meetingId());
        return ApiResponse.ok(Map.of("dialogueId", session.getId()));
    }

    @GetMapping("/dialogue/{id}")
    public ApiResponse<?> getSession(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.getSessionWithMessages(id));
    }

    @GetMapping("/dialogues")
    public ApiResponse<?> listSessions() {
        return ApiResponse.ok(sessionService.listSessions());
    }

    @PostMapping("/dialogue/{id}/archive")
    public ApiResponse<Void> archiveSession(@PathVariable Long id) {
        sessionService.archiveSession(id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/dialogue/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable Long id) {
        sessionService.deleteSession(id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/dialogue/{id}/title")
    public ApiResponse<Void> updateTitle(@PathVariable Long id, @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateTitle(id, request.title().trim());
        return ApiResponse.ok(null);
    }
}
