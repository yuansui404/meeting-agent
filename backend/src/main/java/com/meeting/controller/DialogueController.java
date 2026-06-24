package com.meeting.controller;

import com.meeting.entity.Dialogue;
import com.meeting.entity.RewriteResult;
import com.meeting.service.DialogueService;
import com.meeting.service.RewriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DialogueController {

    private final DialogueService dialogueService;
    private final RewriteService rewriteService;

    public DialogueController(DialogueService dialogueService, RewriteService rewriteService) {
        this.dialogueService = dialogueService;
        this.rewriteService = rewriteService;
    }

    @PostMapping("/dialogue")
    public ResponseEntity<?> createDialogue(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "新对话");
        Long meetingId = request.get("meetingId") != null
                ? Long.valueOf(request.get("meetingId").toString()) : null;

        Dialogue dialogue = dialogueService.createDialogue(title, meetingId);
        return ResponseEntity.ok(Map.of("dialogueId", dialogue.getId()));
    }

    @PostMapping("/dialogue/{id}/message")
    public ResponseEntity<?> addMessage(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            var message = dialogueService.addMessage(
                    id,
                    request.get("role"),
                    request.get("content"),
                    request.get("messageType")
            );
            return ResponseEntity.ok(Map.of(
                    "messageId", message.getId(),
                    "timestamp", message.getTimestamp()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dialogue/{id}")
    public ResponseEntity<?> getDialogue(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(dialogueService.getDialogueHistory(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/dialogues")
    public ResponseEntity<?> listDialogues() {
        return ResponseEntity.ok(dialogueService.listActiveDialogues());
    }

    @PostMapping("/dialogue/{id}/archive")
    public ResponseEntity<?> archiveDialogue(@PathVariable Long id) {
        dialogueService.archiveDialogue(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/dialogue/{id}")
    public ResponseEntity<?> deleteDialogue(@PathVariable Long id) {
        dialogueService.deleteDialogue(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/dialogue/{id}/title")
    public ResponseEntity<?> updateTitle(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            String title = request.get("title");
            if (title == null || title.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Title cannot be empty"));
            }
            dialogueService.updateTitle(id, title.trim());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/dialogue/{id}/import")
    public ResponseEntity<?> importDialogue(@PathVariable Long id) {
        dialogueService.importToKnowledgeBase(id);
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
}
