package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.response.HealthVO;
import com.meeting.conversation.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingController {

    private final SessionService sessionService;

    @GetMapping("/health")
    public HealthVO health() {
        return new HealthVO("OK", "meeting-agent");
    }

    @GetMapping("/dialogue/{dialogueId}/meetings")
    public ApiResponse<?> listDialogueMeetings(@PathVariable Long dialogueId) {
        return ApiResponse.ok(sessionService.extractFilesFromState(dialogueId));
    }
}
