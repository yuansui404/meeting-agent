package com.meeting.conversation.service;

import com.meeting.common.FileMetadata;
import com.meeting.conversation.model.AgentResponseMetadata;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

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
     * 立即持久化用户消息。在 LLM 开始生成前调用，确保刷新页面后用户消息可见。
     */
    public void persistUserMessage(Long dialogueId, String userMessage, List<FileMetadata> messageFiles) {
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
                session.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("Failed to persist user message for dialogue {}: {}", dialogueId, e.getMessage(), e);
        }
    }

    /**
     * 持久化助手回复到 dialogue_messages 表，并更新 session 的 updatedAt。
     * 用户消息已在 persistUserMessage() 中提前持久化，此处仅追加 assistant 消息。
     */
    public void persistAssistantResponse(Long dialogueId, String assistantResponse,
                                          String thinkingText,
                                          List<AgentResponseMetadata.ToolCallRecord> toolCalls,
                                          List<FileMetadata> files) {
        try {
            txTemplate.executeWithoutResult(status -> {
                SessionEntity session = sessionRepository.findById(dialogueId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + dialogueId));

                if (!assistantResponse.isEmpty()) {
                    DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                    asstMsg.setSession(session);
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(assistantResponse);
                    asstMsg.setMessageType("text");

                    // 构建 metadata
                    AgentResponseMetadata metadata = new AgentResponseMetadata(
                            "chat",
                            (thinkingText != null && !thinkingText.isEmpty()) ? thinkingText : null,
                            (toolCalls != null && !toolCalls.isEmpty()) ? toolCalls : null
                    );
                    if (metadata.thinking() != null || metadata.toolCalls() != null) {
                        asstMsg.setMetadata(metadata);
                    }

                    // 保存 AI 生成的文件
                    if (files != null && !files.isEmpty()) {
                        asstMsg.setFiles(files);
                    }

                    session.addMessage(asstMsg);
                }

                session.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("Failed to persist assistant response for dialogue {}: {}", dialogueId, e.getMessage(), e);
        }
    }

    /**
     * 在同一个事务中持久化用户消息和助手错误响应。
     * 用于错误场景，确保数据一致性。
     */
    public void persistErrorDialogue(Long dialogueId, String userMessage,
                                     List<FileMetadata> messageFiles, String errorResponse) {
        try {
            txTemplate.executeWithoutResult(status -> {
                SessionEntity session = sessionRepository.findById(dialogueId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + dialogueId));

                // 保存用户消息
                DialogueMessageEntity dmUser = new DialogueMessageEntity();
                dmUser.setSession(session);
                dmUser.setRole("user");
                dmUser.setContent(userMessage);
                dmUser.setMessageType("text");
                dmUser.setFiles(messageFiles);
                session.addMessage(dmUser);

                // 保存错误响应
                DialogueMessageEntity errorMsg = new DialogueMessageEntity();
                errorMsg.setSession(session);
                errorMsg.setRole("assistant");
                errorMsg.setContent(errorResponse);
                errorMsg.setMessageType("text");
                session.addMessage(errorMsg);

                session.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("Failed to persist error dialogue for {}: {}", dialogueId, e.getMessage(), e);
        }
    }
}
