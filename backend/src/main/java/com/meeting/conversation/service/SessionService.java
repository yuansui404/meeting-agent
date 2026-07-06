package com.meeting.conversation.service;

import com.meeting.common.FileMetadata;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.conversation.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<FileMetadata> extractFilesFromState(Long sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        List<DialogueMessageEntity> msgs = dialogueMessageRepository
                .findBySessionAndRoleAndFilesIsNotNull(session, "user");
        List<FileMetadata> files = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (DialogueMessageEntity msg : msgs) {
            List<FileMetadata> fileList = msg.getFiles();
            if (fileList == null) continue;
            for (FileMetadata fm : fileList) {
                if (fm.fileId() == null || seenIds.contains(fm.fileId())) continue;
                seenIds.add(fm.fileId());

                files.add(new FileMetadata(
                        fm.fileId(), fm.fileName(), fm.filePath(), fm.ext(),
                        fm.fileSize(), fm.status()
                ));
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
            List<FileMetadata> fileList = msg.getFiles();
            if (fileList == null) continue;
            for (FileMetadata fm : fileList) {
                if (fileId.equals(fm.fileId())) {
                    return fm.filePath();
                }
            }
        }
        return null;
    }
}
