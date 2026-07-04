package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.controller.dto.request.AddMessageRequest;
import com.meeting.controller.dto.request.CreateSessionRequest;
import com.meeting.controller.dto.request.UpdateTitleRequest;
import com.meeting.service.RewriteService;
import com.meeting.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final RewriteService rewriteService;

    @PostMapping("/dialogue")
    public ApiResponse<Map<String, Object>> createSession(@Valid @RequestBody CreateSessionRequest request) {
        String title = request.title() != null ? request.title() : "新对话";
        var session = sessionService.createSession(title, request.meetingId());
        return ApiResponse.ok(Map.of("dialogueId", session.getId()));
    }

    @PostMapping("/dialogue/{id}/message")
    public ApiResponse<Map<String, Object>> addMessage(@PathVariable Long id, @Valid @RequestBody AddMessageRequest request) {
        sessionService.addMessage(id, request.role(), request.content(), request.messageType(), null);
        return ApiResponse.ok(Map.of("messageId", 0, "timestamp", java.time.LocalDateTime.now().toString()));
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

    @PostMapping("/dialogue/{id}/import")
    public ApiResponse<Void> importSession(@PathVariable Long id) {
        sessionService.importSession(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/rewrite-result/{id}")
    public ApiResponse<?> getRewriteResult(@PathVariable Long id) {
        return rewriteService.getRewriteResult(id)
                .map(result -> {
                    Map<String, Object> data = new java.util.HashMap<>();
                    data.put("id", result.getId());
                    data.put("dialogueId", result.getDialogueId());
                    data.put("sourceFileIds", result.getSourceFileIds());
                    data.put("referenceIds", result.getReferenceIds());
                    data.put("content", result.getContent());
                    data.put("docxPath", result.getDocxPath());
                    data.put("version", result.getVersion());
                    data.put("createdAt", result.getCreatedAt());
                    return ApiResponse.ok(data);
                })
                .orElseThrow(() -> BusinessException.notFound("改写结果不存在: " + id));
    }

    @GetMapping("/dialogue/{id}/rewrite-history")
    public ApiResponse<?> getRewriteHistory(@PathVariable Long id) {
        var results = rewriteService.getRewriteHistory(id).stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "version", r.getVersion(),
                        "docxPath", r.getDocxPath() != null ? r.getDocxPath() : "",
                        "createdAt", r.getCreatedAt()
                )).toList();
        return ApiResponse.ok(results);
    }

    @GetMapping("/rewrite-result/{id}/file")
    public ResponseEntity<?> downloadRewriteFile(@PathVariable Long id) {
        return rewriteService.getRewriteResult(id)
                .map(result -> {
                    String docxPath = result.getDocxPath();
                    if (docxPath == null || docxPath.isBlank()) {
                        throw BusinessException.notFound("文件不存在");
                    }
                    Path filePath = Path.of(docxPath);
                    if (!filePath.toFile().exists()) {
                        throw BusinessException.notFound("文件不存在");
                    }
                    Resource resource = new FileSystemResource(filePath);
                    String filename = "rewrite_" + result.getDialogueId() + "_v" + result.getVersion() + ".docx";
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename*=UTF-8''" + filename)
                            .body(resource);
                })
                .orElseThrow(() -> BusinessException.notFound("改写结果不存在: " + id));
    }
}
