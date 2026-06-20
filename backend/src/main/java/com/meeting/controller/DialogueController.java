package com.meeting.controller;

import com.meeting.entity.Dialogue;
import com.meeting.service.DialogueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DialogueController {

    private final DialogueService dialogueService;

    public DialogueController(DialogueService dialogueService) {
        this.dialogueService = dialogueService;
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

    @PostMapping("/dialogue/{id}/import")
    public ResponseEntity<?> importDialogue(@PathVariable Long id) {
        dialogueService.importToKnowledgeBase(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
