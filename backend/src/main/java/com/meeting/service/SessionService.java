package com.meeting.service;

import com.meeting.entity.SessionEntity;
import com.meeting.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public SessionEntity createSession(String title, Long meetingId) {
        SessionEntity entity = new SessionEntity();
        entity.setTitle(title != null ? title : "新对话");
        entity.setStatus("active");
        entity.setImported(false);
        entity.setMessageCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity = sessionRepository.save(entity);
        // sessionId relies on id, set it after save
        entity.setSessionId("dialogue-" + entity.getId());
        return sessionRepository.save(entity);
    }

    public Map<String, Object> getSessionWithMessages(Long id) {
        SessionEntity entity = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));

        List<Map<String, Object>> messages = extractMessages(entity);

        Map<String, Object> dialogue = new HashMap<>();
        dialogue.put("id", entity.getId());
        dialogue.put("title", entity.getTitle());
        dialogue.put("status", entity.getStatus());
        dialogue.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        dialogue.put("meetingId", null);
        dialogue.put("imported", entity.getImported() != null && entity.getImported());

        Map<String, Object> result = new HashMap<>();
        result.put("dialogue", dialogue);
        result.put("messages", messages);
        return result;
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
                    m.put("imported", e.getImported() != null && e.getImported());
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

    /**
     * Directly save a message into the session's AgentState.
     * Used by RewriteService and other components that run outside the agent loop.
     */
    @Transactional
    public void addMessage(Long sessionId, String role, String content, String messageType, String metadata) {
        SessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        addMessageToSession(entity, role, content, messageType, metadata);
        sessionRepository.save(entity);
    }

    private void addMessageToSession(SessionEntity entity, String role, String content,
                                      String messageType, String metadata) {
        try {
            String json = entity.getStateJson();
            AgentState state = (json != null && !json.isBlank())
                    ? AgentState.fromJsonString(json)
                    : AgentState.builder().sessionId(entity.getSessionId()).build();
            // Append message to context
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

    private List<Map<String, Object>> extractMessages(SessionEntity entity) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String json = entity.getStateJson();
        if (json == null || json.isBlank()) return messages;

        try {
            AgentState state = AgentState.fromJsonString(json);
            List<Msg> context = state.getContext();
            int idx = 0;
            for (Msg msg : context) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", ++idx);
                m.put("dialogueId", entity.getId());
                m.put("role", msg.getRole());
                m.put("content", msg.getTextContent());
                m.put("messageType", "text");
                m.put("timestamp", msg.getTimestamp());
                m.put("metadata", null);
                messages.add(m);
            }
        } catch (Exception e) {
            log.warn("Failed to extract messages from AgentState for session {}: {}", entity.getId(), e.getMessage());
        }
        return messages;
    }
}
