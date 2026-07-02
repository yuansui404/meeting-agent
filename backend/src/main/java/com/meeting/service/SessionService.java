package com.meeting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.entity.DialogueMessageEntity;
import com.meeting.entity.SessionEntity;
import com.meeting.repository.DialogueMessageRepository;
import com.meeting.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SessionRepository sessionRepository;
    private final DialogueMessageRepository dialogueMessageRepository;

    public SessionService(SessionRepository sessionRepository,
                          DialogueMessageRepository dialogueMessageRepository) {
        this.sessionRepository = sessionRepository;
        this.dialogueMessageRepository = dialogueMessageRepository;
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

        List<Map<String, Object>> messages = queryMessages(id);

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

    /**
     * Build messages list from dialogue_messages table.
     */
    private List<Map<String, Object>> queryMessages(Long dialogueId) {
        List<DialogueMessageEntity> entities = dialogueMessageRepository.findByDialogueIdOrderById(dialogueId);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (DialogueMessageEntity msg : entities) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", msg.getId());
            m.put("dialogueId", dialogueId);
            m.put("role", msg.getRole().toLowerCase());
            m.put("content", msg.getContent() != null ? msg.getContent() : "");
            m.put("messageType", msg.getMessageType() != null ? msg.getMessageType() : "text");
            m.put("timestamp", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
            m.put("metadata", msg.getMetadata());
            // Parse files JSON into list
            if (msg.getFiles() != null && !msg.getFiles().isBlank()) {
                try {
                    m.put("files", objectMapper.readValue(msg.getFiles(), List.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse files JSON for message {}: {}", msg.getId(), e.getMessage());
                }
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
        dialogueMessageRepository.deleteByDialogueId(id);
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
     * Add a message to both state_json (agent context) and dialogue_messages (rendering).
     */
    @Transactional
    public void addMessage(Long sessionId, String role, String content, String messageType, String metadata) {
        SessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        addMessageToSession(entity, role, content, messageType, metadata);
        sessionRepository.save(entity);

        // Also write to dialogue_messages
        DialogueMessageEntity dmsg = new DialogueMessageEntity();
        dmsg.setDialogueId(sessionId);
        dmsg.setRole(role);
        dmsg.setContent(content);
        dmsg.setMessageType(messageType);
        dmsg.setMetadata(metadata);
        dialogueMessageRepository.save(dmsg);
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

    /**
     * Extract all files from dialogue_messages table.
     */
    public List<Map<String, Object>> extractFilesFromState(Long sessionId) {
        List<DialogueMessageEntity> msgs = dialogueMessageRepository
                .findByDialogueIdAndRoleAndFilesIsNotNull(sessionId, "user");
        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (DialogueMessageEntity msg : msgs) {
            List<Map<String, Object>> fileList = parseFilesJson(msg.getFiles());
            if (fileList == null) continue;
            for (Map<String, Object> fm : fileList) {
                String fileId = (String) fm.get("fileId");
                if (fileId == null || seenIds.contains(fileId)) continue;
                seenIds.add(fileId);

                // Check for sidecar transcription file
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

    /**
     * Find a file path in dialogue_messages by fileId.
     */
    public String findFilePathInState(Long sessionId, String fileId) {
        List<DialogueMessageEntity> msgs = dialogueMessageRepository
                .findByDialogueIdAndRoleAndFilesIsNotNull(sessionId, "user");
        for (DialogueMessageEntity msg : msgs) {
            List<Map<String, Object>> fileList = parseFilesJson(msg.getFiles());
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFilesJson(String filesJson) {
        if (filesJson == null || filesJson.isBlank()) return null;
        try {
            return objectMapper.readValue(filesJson, List.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse files JSON: {}", e.getMessage());
            return null;
        }
    }
}
