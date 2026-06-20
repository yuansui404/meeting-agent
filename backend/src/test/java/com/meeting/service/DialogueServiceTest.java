package com.meeting.service;

import com.meeting.entity.Dialogue;
import com.meeting.entity.DialogueMessage;
import com.meeting.repository.DialogueMessageRepository;
import com.meeting.repository.DialogueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DialogueServiceTest {

    @Mock
    private DialogueRepository dialogueRepository;

    @Mock
    private DialogueMessageRepository messageRepository;

    private DialogueService dialogueService;

    @BeforeEach
    void setUp() {
        dialogueService = new DialogueService(dialogueRepository, messageRepository);
    }

    @Test
    void createDialogue_ShouldReturnSavedDialogue() {
        Dialogue saved = new Dialogue();
        saved.setId(1L);
        saved.setTitle("测试对话");
        saved.setStatus("active");

        when(dialogueRepository.save(any(Dialogue.class))).thenReturn(saved);

        Dialogue result = dialogueService.createDialogue("测试对话", null);

        assertNotNull(result);
        assertEquals("测试对话", result.getTitle());
        assertEquals("active", result.getStatus());
        verify(dialogueRepository).save(any(Dialogue.class));
    }

    @Test
    void addMessage_ShouldSaveAndReturnMessage() {
        Dialogue dialogue = new Dialogue();
        dialogue.setId(1L);

        DialogueMessage message = new DialogueMessage();
        message.setId(1L);
        message.setDialogueId(1L);
        message.setRole("user");
        message.setContent("你好");

        when(dialogueRepository.findById(1L)).thenReturn(Optional.of(dialogue));
        when(messageRepository.save(any(DialogueMessage.class))).thenReturn(message);

        DialogueMessage result = dialogueService.addMessage(1L, "user", "你好", null);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertEquals("你好", result.getContent());
        verify(dialogueRepository).save(dialogue);
    }

    @Test
    void getDialogueHistory_ShouldReturnDialogueAndMessages() {
        Dialogue dialogue = new Dialogue();
        dialogue.setId(1L);

        DialogueMessage msg = new DialogueMessage();
        msg.setId(1L);
        msg.setDialogueId(1L);

        when(dialogueRepository.findById(1L)).thenReturn(Optional.of(dialogue));
        when(messageRepository.findByDialogueIdOrderByTimestampAsc(1L)).thenReturn(List.of(msg));

        Map<String, Object> result = dialogueService.getDialogueHistory(1L);

        assertNotNull(result);
        assertEquals(dialogue, result.get("dialogue"));
        assertEquals(1, ((List<?>) result.get("messages")).size());
    }

    @Test
    void getDialogueHistory_ShouldThrowWhenNotFound() {
        when(dialogueRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                dialogueService.getDialogueHistory(99L));
    }
}
