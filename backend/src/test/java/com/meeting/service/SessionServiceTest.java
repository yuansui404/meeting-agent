package com.meeting.service;

import com.meeting.entity.SessionEntity;
import com.meeting.repository.SessionRepository;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Captor
    private ArgumentCaptor<SessionEntity> entityCaptor;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRepository);
    }

    @Test
    void createSession_ShouldSetSessionId() {
        SessionEntity saved = new SessionEntity();
        saved.setId(1L);
        saved.setTitle("新对话");
        saved.setStatus("active");

        when(sessionRepository.save(any())).thenReturn(saved);

        SessionEntity result = sessionService.createSession("测试对话", null);

        verify(sessionRepository, times(2)).save(any());
        assertEquals("dialogue-1", result.getSessionId());
    }

    @Test
    void createSession_ShouldUseDefaultTitle() {
        SessionEntity saved = new SessionEntity();
        saved.setId(2L);
        saved.setTitle("新对话");
        saved.setStatus("active");

        when(sessionRepository.save(any())).thenReturn(saved);

        SessionEntity result = sessionService.createSession(null, null);

        verify(sessionRepository, times(2)).save(any());
        assertEquals("dialogue-2", result.getSessionId());
    }

    @Test
    void getSessionWithMessages_ShouldReturnDialogueAndMessages() {
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();
        state.contextMutable().add(new UserMessage("hello"));
        String json = state.toJson();

        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setTitle("测试");
        entity.setStatus("active");
        entity.setStateJson(json);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));

        Map<String, Object> result = sessionService.getSessionWithMessages(1L);

        assertNotNull(result.get("dialogue"));
        assertNotNull(result.get("messages"));

        @SuppressWarnings("unchecked")
        Map<String, Object> dialogue = (Map<String, Object>) result.get("dialogue");
        assertEquals("测试", dialogue.get("title"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0).get("content"));
    }

    @Test
    void getSessionWithMessages_ShouldReturnEmptyMessagesWhenNoState() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setTitle("测试");
        entity.setStatus("active");
        entity.setStateJson(null);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));

        Map<String, Object> result = sessionService.getSessionWithMessages(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
    }

    @Test
    void getSessionWithMessages_ShouldThrowWhenNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> sessionService.getSessionWithMessages(99L));
    }

    @Test
    void listSessions_ShouldReturnAllSessions() {
        SessionEntity e1 = new SessionEntity();
        e1.setId(1L);
        e1.setTitle("对话1");
        e1.setStatus("active");

        SessionEntity e2 = new SessionEntity();
        e2.setId(2L);
        e2.setTitle("对话2");
        e2.setStatus("active");

        when(sessionRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(e1, e2));

        List<Map<String, Object>> result = sessionService.listSessions();
        assertEquals(2, result.size());
        assertEquals("对话1", result.get(0).get("title"));
    }

    @Test
    void archiveSession_ShouldSetStatusToArchived() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setStatus("active");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));

        sessionService.archiveSession(1L);
        assertEquals("archived", entity.getStatus());
        verify(sessionRepository).save(entity);
    }

    @Test
    void archiveSession_ShouldDoNothingWhenNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        sessionService.archiveSession(99L);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void deleteSession_ShouldDeleteById() {
        sessionService.deleteSession(1L);
        verify(sessionRepository).deleteById(1L);
    }

    @Test
    void updateTitle_ShouldUpdateAndReturn() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setTitle("旧标题");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SessionEntity result = sessionService.updateTitle(1L, "新标题");
        assertEquals("新标题", result.getTitle());
        verify(sessionRepository).save(entity);
    }

    @Test
    void updateTitle_ShouldThrowWhenNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> sessionService.updateTitle(99L, "标题"));
    }

    @Test
    void addMessage_ShouldAppendToState() {
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();
        String json = state.toJson();

        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson(json);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        sessionService.addMessage(1L, "user", "new message", "text", null);

        verify(sessionRepository).save(entityCaptor.capture());
        SessionEntity saved = entityCaptor.getValue();
        assertNotNull(saved.getStateJson());
        assertTrue(saved.getStateJson().contains("new message"));
    }

    @Test
    void addMessage_ShouldCreateStateWhenNoneExists() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson(null);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        sessionService.addMessage(1L, "assistant", "reply", "text", null);

        verify(sessionRepository).save(entityCaptor.capture());
        SessionEntity saved = entityCaptor.getValue();
        assertNotNull(saved.getStateJson());
        assertTrue(saved.getStateJson().contains("reply"));
    }

    @Test
    void addMessage_ShouldThrowWhenSessionNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> sessionService.addMessage(99L, "user", "content", "text", null));
    }

    @Test
    void importSession_ShouldSetImportedFlag() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setImported(false);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        sessionService.importSession(1L);
        assertTrue(entity.getImported());
    }

    @Test
    void importSession_ShouldThrowWhenNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> sessionService.importSession(99L));
    }
}
