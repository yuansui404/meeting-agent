package com.meeting.controller;

import com.meeting.entity.RewriteResult;
import com.meeting.service.RewriteService;
import com.meeting.service.SessionService;
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
public class SessionController {

    private final SessionService sessionService;
    private final RewriteService rewriteService;

    public SessionController(SessionService sessionService, RewriteService rewriteService) {
        this.sessionService = sessionService;
        this.rewriteService = rewriteService;
    }

    @PostMapping("/dialogue")
    public ResponseEntity<?> createSession(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "新对话");
        Long meetingId = request.get("meetingId") != null
                ? Long.valueOf(request.get("meetingId").toString()) : null;
        var session = sessionService.createSession(title, meetingId);
        return ResponseEntity.ok(Map.of("dialogueId", session.getId()));
    }

    @PostMapping("/dialogue/{id}/message")
    public ResponseEntity<?> addMessage(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            sessionService.addMessage(id, request.get("role"), request.get("content"), request.get("messageType"), null);
            return ResponseEntity.ok(Map.of("messageId", 0, "timestamp", java.time.LocalDateTime.now().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dialogue/{id}")
    public ResponseEntity<?> getSession(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(sessionService.getSessionWithMessages(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/dialogues")
    public ResponseEntity<?> listSessions() {
        return ResponseEntity.ok(sessionService.listSessions());
    }

    @PostMapping("/dialogue/{id}/archive")
    public ResponseEntity<?> archiveSession(@PathVariable Long id) {
        sessionService.archiveSession(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/dialogue/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id) {
        sessionService.deleteSession(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/dialogue/{id}/title")
    public ResponseEntity<?> updateTitle(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            String title = request.get("title");
            if (title == null || title.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Title cannot be empty"));
            }
            sessionService.updateTitle(id, title.trim());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/dialogue/{id}/import")
    public ResponseEntity<?> importSession(@PathVariable Long id) {
        sessionService.importSession(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/rewrite-result/{id}")
    public ResponseEntity<?> getRewriteResult(@PathVariable Long id) {
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
                    return ResponseEntity.ok(data);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/dialogue/{id}/rewrite-history")
    public ResponseEntity<?> getRewriteHistory(@PathVariable Long id) {
        var results = rewriteService.getRewriteHistory(id).stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "version", r.getVersion(),
                        "docxPath", r.getDocxPath() != null ? r.getDocxPath() : "",
                        "createdAt", r.getCreatedAt()
                )).toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rewrite-result/{id}/file")
    public ResponseEntity<?> downloadRewriteFile(@PathVariable Long id) {
        return rewriteService.getRewriteResult(id)
                .map(result -> {
                    String docxPath = result.getDocxPath();
                    if (docxPath == null || docxPath.isBlank()) {
                        return ResponseEntity.notFound().build();
                    }
                    Path filePath = Path.of(docxPath);
                    if (!filePath.toFile().exists()) {
                        return ResponseEntity.notFound().build();
                    }
                    Resource resource = new FileSystemResource(filePath);
                    String filename = "rewrite_" + result.getDialogueId() + "_v" + result.getVersion() + ".docx";
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename*=UTF-8''" + filename)
                            .body(resource);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
