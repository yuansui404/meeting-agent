package com.meeting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.conversation.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 负责对话消息持久化和 session 时间戳更新。
 * 从 ChatService 中抽取，独立管理事务边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialoguePersistenceService {

    private final DialogueMessageRepository dialogueMessageRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    /**
     * 持久化用户消息和助手回复到 dialogue_messages 表，并更新 session 的 updatedAt。
     */
    public void persist(Long dialogueId, String userMessage, String assistantResponse,
                        List<Map<String, Object>> messageFiles) {
        try {
            txTemplate.executeWithoutResult(status -> {
                try {
                    String filesJson = messageFiles != null && !messageFiles.isEmpty()
                            ? objectMapper.writeValueAsString(messageFiles) : null;

                    DialogueMessageEntity dmUser = new DialogueMessageEntity();
                    dmUser.setDialogueId(dialogueId);
                    dmUser.setRole("user");
                    dmUser.setContent(userMessage);
                    dmUser.setMessageType("text");
                    dmUser.setFiles(filesJson);
                    dialogueMessageRepository.save(dmUser);

                    if (!assistantResponse.isEmpty()) {
                        DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                        asstMsg.setDialogueId(dialogueId);
                        asstMsg.setRole("assistant");
                        asstMsg.setContent(assistantResponse);
                        asstMsg.setMessageType("text");
                        dialogueMessageRepository.save(asstMsg);
                    }

                    sessionRepository.findById(dialogueId).ifPresent(session -> {
                        session.setUpdatedAt(LocalDateTime.now());
                        sessionRepository.save(session);
                    });
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to persist dialogue_messages for dialogue {}: {}",
                    dialogueId, e.getMessage(), e);
        }
    }
}
