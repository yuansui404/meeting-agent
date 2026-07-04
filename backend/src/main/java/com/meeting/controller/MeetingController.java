package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.controller.dto.request.RewriteFeedbackRequest;
import com.meeting.controller.dto.response.HealthVO;
import com.meeting.controller.dto.response.MeetingDetailVO;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.MeetingService;
import com.meeting.service.RewriteFeedbackService;
import com.meeting.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingService meetingService;
    private final RewriteFeedbackService rewriteFeedbackService;
    private final SessionService sessionService;

    @GetMapping("/meeting/{id}")
    public MeetingDetailVO getMeeting(@PathVariable Long id) {
        return meetingRepository.findById(id)
                .map(m -> new MeetingDetailVO(
                        m.getId(), m.getTitle(), m.getTranscription(),
                        m.getDuration(), m.getFileSize(), m.getStatus(),
                        m.getCreatedAt(), m.getDialogueId(), m.getMdFilePath(),
                        m.getMeetingDate()))
                .orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));
    }

    @GetMapping("/meetings")
    public ApiResponse<?> listMeetings() {
        return ApiResponse.ok(meetingRepository.findAll());
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
        if (!meetingRepository.existsById(id)) {
            throw BusinessException.notFound("会议不存在: " + id);
        }
        meetingService.deleteMeeting(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rewrite-feedback")
    public ApiResponse<Void> submitRewriteFeedback(@Valid @RequestBody RewriteFeedbackRequest request) {
        rewriteFeedbackService.submitFeedback(request.rewriteResultId(), request.paragraphIndex(), request.action());
        return ApiResponse.ok(null);
    }
}
