package com.meeting.service;

import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.conversation.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final DialogueMessageRepository dialogueMessageRepository;

    @Transactional
    public SessionEntity createSession(String title, Long meetingId) {
        SessionEntity entity = new SessionEntity();
        entity.setTitle(title != null ? title : "新对话");
        entity.setStatus("active");
        entity.setMessageCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity = sessionRepository.save(entity);
        entity.setSessionId("dialogue-" + entity.getId());
        return sessionRepository.save(entity);
    }

    public Map<String, Object> getSessionWithMessages(Long id) {
        SessionEntity entity = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));

        List<Map<String, Object>> messages = queryMessages(entity);

        Map<String, Object> dialogue = new HashMap<>();
        dialogue.put("id", entity.getId());
        dialogue.put("title", entity.getTitle());
        dialogue.put("status", entity.getStatus());
        dialogue.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        dialogue.put("meetingId", null);
        dialogue.put("imported", entity.isImported());

        Map<String, Object> result = new HashMap<>();
        result.put("dialogue", dialogue);
        result.put("messages", messages);
        return result;
    }

    private List<Map<String, Object>> queryMessages(SessionEntity session) {
        List<DialogueMessageEntity> entities = dialogueMessageRepository.findBySessionOrderById(session);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (DialogueMessageEntity msg : entities) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", msg.getId());
            m.put("dialogueId", session.getId());
            m.put("role", msg.getRole() != null ? msg.getRole().toLowerCase() : "unknown");
            m.put("content", msg.getContent() != null ? msg.getContent() : "");
            m.put("messageType", msg.getMessageType() != null ? msg.getMessageType() : "text");
            m.put("timestamp", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
            m.put("metadata", msg.getMetadata());
            if (msg.getFiles() != null && !msg.getFiles().isEmpty()) {
                m.put("files", msg.getFiles());
            }
            messages.add(m);
        }
        return messages;
    }

    public List<Map<String, Object>> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", e.getId());
                    m.put("title", e.getTitle());
                    m.put("status", e.getStatus());
                    m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
                    m.put("meetingId", null);
                    m.put("imported", e.isImported());
                    return m;
                })
                .toList();
    }

    @Transactional
    public void archiveSession(Long id) {
        sessionRepository.findById(id).ifPresent(e -> {
            e.setStatus("archived");
            sessionRepository.save(e);
        });
    }

    @Transactional
    public void importSession(Long id) {
        SessionEntity entity = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        entity.setImported(true);
        sessionRepository.save(entity);
    }

    @Transactional
    public void deleteSession(Long id) {
        sessionRepository.deleteById(id);
    }

    @Transactional
    public SessionEntity updateTitle(Long id, String title) {
        SessionEntity entity = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        entity.setTitle(title);
        return sessionRepository.save(entity);
    }

    @Transactional
    public void addMessage(Long sessionId, String role, String content, String messageType, String metadata) {
        SessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        addMessageToSession(entity, role, content, messageType, metadata);
        sessionRepository.save(entity);

        DialogueMessageEntity dmsg = new DialogueMessageEntity();
        dmsg.setRole(role);
        dmsg.setContent(content);
        dmsg.setMessageType(messageType);
        if (metadata != null && !metadata.isBlank()) {
            try {
                dmsg.setMetadata(new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadata, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON for session {}: {}", sessionId, e.getMessage());
            }
        }
        entity.addMessage(dmsg);
    }

    private void addMessageToSession(SessionEntity entity, String role, String content,
                                      String messageType, String metadata) {
        try {
            String json = entity.getStateJson();
            AgentState state = (json != null && !json.isBlank())
                    ? AgentState.fromJsonString(json)
                    : AgentState.builder().sessionId(entity.getSessionId()).build();
            state.contextMutable().add(buildMsg(role, content, messageType, metadata));
            entity.setStateJson(state.toJson());
            entity.setMessageCount(state.getContext().size());
        } catch (Exception e) {
            log.warn("Failed to add message to AgentState for session {}: {}", entity.getId(), e.getMessage());
        }
    }

    private Msg buildMsg(String role, String content, String messageType, String metadata) {
        if ("user".equals(role)) {
            return new io.agentscope.core.message.UserMessage(content);
        }
        return new io.agentscope.core.message.AssistantMessage(content);
    }

    public List<Map<String, Object>> extractFilesFromState(Long sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        List<DialogueMessageEntity> msgs = dialogueMessageRepository
                .findBySessionAndRoleAndFilesIsNotNull(session, "user");
        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (DialogueMessageEntity msg : msgs) {
            List<Map<String, Object>> fileList = msg.getFiles();
            if (fileList == null) continue;
            for (Map<String, Object> fm : fileList) {
                String fileId = (String) fm.get("fileId");
                if (fileId == null || seenIds.contains(fileId)) continue;
                seenIds.add(fileId);

                String filePath = (String) fm.get("filePath");
                if (filePath != null) {
                    Path transcriptionPath = Path.of(filePath + ".transcription.md");
                    if (Files.exists(transcriptionPath)) {
                        fm.put("transcriptionFilePath", transcriptionPath.toString());
                    }
                }

                files.add(fm);
            }
        }
        return files;
    }

    public String findFilePathInState(Long sessionId, String fileId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        List<DialogueMessageEntity> msgs = dialogueMessageRepository
                .findBySessionAndRoleAndFilesIsNotNull(session, "user");
        for (DialogueMessageEntity msg : msgs) {
            List<Map<String, Object>> fileList = msg.getFiles();
            if (fileList == null) continue;
            for (Map<String, Object> fm : fileList) {
                if (fileId.equals(fm.get("fileId"))) {
                    Object path = fm.get("filePath");
                    return path instanceof String ? (String) path : null;
                }
            }
        }
        return null;
    }
}
