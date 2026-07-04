package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.request.StyleExemplarRequest;
import com.meeting.controller.dto.response.KBUploadVO;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.service.KnowledgeBaseService;
import com.meeting.service.VectorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorizationService vectorizationService;

    @PostMapping("/meetings/knowledge-base/upload")
    public ApiResponse<KBUploadVO> uploadToKnowledgeBase(@RequestParam("file") MultipartFile file) throws Exception {
        MeetingMinutes meeting = knowledgeBaseService.uploadDocument(file);
        return ApiResponse.ok(new KBUploadVO(meeting.getId(), meeting.getTitle()));
    }

    @PostMapping("/meeting/{id}/vectorize")
    public ApiResponse<Void> vectorizeMeeting(@PathVariable Long id) {
        vectorizationService.vectorizeMeeting(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/meeting/{id}/style-exemplar")
    public ApiResponse<Map<String, Object>> setStyleExemplar(@PathVariable Long id, @Valid @RequestBody StyleExemplarRequest request) {
        boolean exemplar = Boolean.TRUE.equals(request.styleExemplar());
        knowledgeBaseService.setStyleExemplar(id, exemplar, request.styleTags());
        return ApiResponse.ok(Map.of("styleExemplar", exemplar));
    }

    @GetMapping("/meetings/style-exemplars")
    public ApiResponse<?> listStyleExemplars() {
        return ApiResponse.ok(knowledgeBaseService.listStyleExemplars());
    }

    @PostMapping("/meetings/backfill-participants")
    public ApiResponse<Map<String, Object>> backfillParticipants() {
        var results = knowledgeBaseService.backfillParticipants();
        return ApiResponse.ok(Map.of("processed", results.size(), "results", results));
    }
}
