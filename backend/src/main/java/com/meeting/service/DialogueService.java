package com.meeting.service;

import com.meeting.entity.Dialogue;
import com.meeting.entity.DialogueMessage;
import com.meeting.repository.DialogueMessageRepository;
import com.meeting.repository.DialogueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DialogueService {

    private final DialogueRepository dialogueRepository;
    private final DialogueMessageRepository messageRepository;

    public DialogueService(DialogueRepository dialogueRepository,
                           DialogueMessageRepository messageRepository) {
        this.dialogueRepository = dialogueRepository;
        this.messageRepository = messageRepository;
    }

    public Dialogue createDialogue(String title, Long meetingId) {
        Dialogue dialogue = new Dialogue();
        dialogue.setTitle(title);
        dialogue.setMeetingId(meetingId);
        dialogue.setStatus("active");
        return dialogueRepository.save(dialogue);
    }

    @Transactional
    public DialogueMessage addMessage(Long dialogueId, String role, String content, String messageType) {
        Dialogue dialogue = dialogueRepository.findById(dialogueId)
                .orElseThrow(() -> new IllegalArgumentException("Dialogue not found: " + dialogueId));

        DialogueMessage message = new DialogueMessage();
        message.setDialogueId(dialogueId);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType(messageType != null ? messageType : "text");
        message.setTimestamp(LocalDateTime.now());
        message = messageRepository.save(message);

        // 更新对话时间戳
        dialogue.setUpdatedAt(LocalDateTime.now());
        dialogueRepository.save(dialogue);

        return message;
    }

    public Map<String, Object> getDialogueHistory(Long dialogueId) {
        Dialogue dialogue = dialogueRepository.findById(dialogueId)
                .orElseThrow(() -> new IllegalArgumentException("Dialogue not found: " + dialogueId));

        List<DialogueMessage> messages = messageRepository.findByDialogueIdOrderByTimestampAsc(dialogueId);

        Map<String, Object> result = new HashMap<>();
        result.put("dialogue", dialogue);
        result.put("messages", messages);
        return result;
    }

    public List<Dialogue> listActiveDialogues() {
        return dialogueRepository.findTop10ByOrderByUpdatedAtDesc();
    }

    @Transactional
    public void archiveDialogue(Long dialogueId) {
        dialogueRepository.findById(dialogueId).ifPresent(d -> {
            d.setStatus("archived");
            dialogueRepository.save(d);
        });
    }

    @Transactional
    public void importToKnowledgeBase(Long dialogueId) {
        Dialogue dialogue = dialogueRepository.findById(dialogueId)
                .orElseThrow(() -> new IllegalArgumentException("Dialogue not found: " + dialogueId));

        dialogue.setImported(true);
        dialogueRepository.save(dialogue);
    }

    public String getDialogueContext(Long dialogueId) {
        return messageRepository.findByDialogueIdOrderByTimestampAsc(dialogueId).stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
