package com.meeting.service;

import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
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

    private final SessionRepository sessionRepository;
    private final TransactionTemplate txTemplate;

    /**
     * 持久化用户消息和助手回复到 dialogue_messages 表，并更新 session 的 updatedAt。
     */
    public void persist(Long dialogueId, String userMessage, String assistantResponse,
                        List<Map<String, Object>> messageFiles) {
        try {
            txTemplate.executeWithoutResult(status -> {
                SessionEntity session = sessionRepository.findById(dialogueId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + dialogueId));

                DialogueMessageEntity dmUser = new DialogueMessageEntity();
                dmUser.setSession(session);
                dmUser.setRole("user");
                dmUser.setContent(userMessage);
                dmUser.setMessageType("text");
                dmUser.setFiles(messageFiles);
                session.addMessage(dmUser);

                if (!assistantResponse.isEmpty()) {
                    DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                    asstMsg.setSession(session);
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(assistantResponse);
                    asstMsg.setMessageType("text");
                    session.addMessage(asstMsg);
                }

                session.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("Failed to persist dialogue_messages for dialogue {}: {}",
                    dialogueId, e.getMessage(), e);
        }
    }
}
