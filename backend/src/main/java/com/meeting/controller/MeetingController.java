package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.request.RewriteFeedbackRequest;
import com.meeting.controller.dto.response.HealthVO;
import com.meeting.controller.dto.response.MeetingDetailVO;
import com.meeting.controller.dto.response.MeetingListVO;
import com.meeting.meeting.service.MeetingService;
import com.meeting.conversation.service.RewriteFeedbackService;
import com.meeting.conversation.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final RewriteFeedbackService rewriteFeedbackService;
    private final SessionService sessionService;

    @GetMapping("/meeting/{id}")
    public ApiResponse<MeetingDetailVO> getMeeting(@PathVariable Long id) {
        return ApiResponse.ok(meetingService.getById(id));
    }

    @GetMapping("/meetings")
    public ApiResponse<Page<MeetingListVO>> listMeetings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(meetingService.listAll(pageable));
    }

    @GetMapping("/health")
    public HealthVO health() {
        return new HealthVO("OK", "meeting-agent");
    }

    @GetMapping("/dialogue/{dialogueId}/meetings")
    public ApiResponse<?> listDialogueMeetings(@PathVariable Long dialogueId) {
        return ApiResponse.ok(sessionService.extractFilesFromState(dialogueId));
    }

    @DeleteMapping("/meeting/{id}")
    public ApiResponse<Void> deleteMeeting(@PathVariable Long id) {
        meetingService.deleteMeeting(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rewrite-feedback")
    public ApiResponse<Void> submitRewriteFeedback(@Valid @RequestBody RewriteFeedbackRequest request) {
        rewriteFeedbackService.submitFeedback(request.rewriteResultId(), request.paragraphIndex(), request.action());
        return ApiResponse.ok(null);
    }
}
