package com.meeting.state;

import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.SessionRepository;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgAgentStateStoreTest {

    @Mock
    private SessionRepository sessionRepository;

    @Captor
    private ArgumentCaptor<SessionEntity> entityCaptor;

    private PgAgentStateStore store;

    @BeforeEach
    void setUp() {
        store = new CleanablePgAgentStateStore(sessionRepository);
    }

    @Test
    void save_ShouldPersistStateJson() {
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();
        state.contextMutable().add(new UserMessage("hello"));

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        store.save("default", "dialogue-1", "agent_state", state);

        verify(sessionRepository).save(entityCaptor.capture());
        SessionEntity saved = entityCaptor.getValue();

        assertNotNull(saved.getStateJson());
        assertTrue(saved.getStateJson().contains("hello"));
        assertEquals("dialogue-1", saved.getSessionId());
    }

    @Test
    void save_WithWrongKey_ShouldDoNothing() {
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();
        store.save("default", "dialogue-1", "other_key", state);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void get_ShouldDeserializeState() {
        AgentState original = AgentState.builder().sessionId("dialogue-1").build();
        original.contextMutable().add(new UserMessage("test message"));
        String json = original.toJson();

        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson(json);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.of(entity));

        Optional<AgentState> result = store.get("default", "dialogue-1", "agent_state", AgentState.class);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getContext().size());
        assertEquals("test message", result.get().getContext().get(0).getTextContent());
    }

    @Test
    void get_ShouldReturnEmptyWhenNoStateJson() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson(null);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.of(entity));

        Optional<AgentState> result = store.get("default", "dialogue-1", "agent_state", AgentState.class);
        assertFalse(result.isPresent());
    }

    @Test
    void get_ShouldReturnEmptyWhenStateJsonIsBlank() {
        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson("   ");

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.of(entity));

        Optional<AgentState> result = store.get("default", "dialogue-1", "agent_state", AgentState.class);
        assertFalse(result.isPresent());
    }

    @Test
    void get_WithWrongKey_ShouldReturnEmpty() {
        Optional<AgentState> result = store.get("default", "dialogue-1", "wrong_key", AgentState.class);
        assertFalse(result.isPresent());
        verify(sessionRepository, never()).findBySessionId(any());
    }

    @Test
    void exists_ShouldReturnTrueWhenSessionExists() {
        when(sessionRepository.existsBySessionId("dialogue-1")).thenReturn(true);
        assertTrue(store.exists("default", "dialogue-1"));
    }

    @Test
    void exists_ShouldReturnFalseWhenSessionDoesNotExist() {
        when(sessionRepository.existsBySessionId("dialogue-1")).thenReturn(false);
        assertFalse(store.exists("default", "dialogue-1"));
    }

    @Test
    void delete_ShouldRemoveSession() {
        store.delete("default", "dialogue-1");
        verify(sessionRepository).deleteBySessionId("dialogue-1");
    }

    @Test
    void listSessionIds_ShouldReturnAllIds() {
        when(sessionRepository.findAllSessionIds()).thenReturn(List.of("dialogue-1", "dialogue-2"));

        Set<String> ids = store.listSessionIds("default");
        assertEquals(Set.of("dialogue-1", "dialogue-2"), ids);
    }

    @Test
    void listSessionIds_ShouldReturnEmptyWhenNoSessions() {
        when(sessionRepository.findAllSessionIds()).thenReturn(List.of());
        Set<String> ids = store.listSessionIds("default");
        assertTrue(ids.isEmpty());
    }

    @Test
    void getList_ShouldReturnListWithOneElement() {
        AgentState original = AgentState.builder().sessionId("dialogue-1").build();
        original.contextMutable().add(new UserMessage("test"));
        String json = original.toJson();

        SessionEntity entity = new SessionEntity();
        entity.setId(1L);
        entity.setSessionId("dialogue-1");
        entity.setStateJson(json);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.of(entity));

        List<AgentState> result = store.getList("default", "dialogue-1", "agent_state", AgentState.class);
        assertEquals(1, result.size());
    }

    @Test
    void getList_ShouldReturnEmptyWhenNoState() {
        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        List<AgentState> result = store.getList("default", "dialogue-1", "agent_state", AgentState.class);
        assertTrue(result.isEmpty());
    }

    // --- CleanablePgAgentStateStore tests ---

    @Test
    void cleanable_save_ShouldStripEnrichedContent() {
        CleanablePgAgentStateStore cleanableStore = new CleanablePgAgentStateStore(sessionRepository);
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();

        String enrichedText = "请参考以下资料来回答问题。\n要求：...\n资料内容：\n[大量文件内容]\n\n问题：什么是AI？";
        UserMessage msg = new UserMessage(enrichedText);
        msg.getMetadata().put("_enriched", true);
        msg.getMetadata().put("_originalQuestion", "什么是AI？");
        state.contextMutable().add(msg);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        cleanableStore.save("default", "dialogue-1", "agent_state", state);

        verify(sessionRepository).save(entityCaptor.capture());
        String savedJson = entityCaptor.getValue().getStateJson();

        assertTrue(savedJson.contains("什么是AI？"));
        assertFalse(savedJson.contains("大量文件内容"));
        assertFalse(savedJson.contains("请参考以下资料"));
    }

    @Test
    void cleanable_save_ShouldPreserveFileMetadata() {
        CleanablePgAgentStateStore cleanableStore = new CleanablePgAgentStateStore(sessionRepository);
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();

        UserMessage msg = new UserMessage("请参考以下资料来回答问题。\n资料内容：xxx\n\n问题：总结一下");
        msg.getMetadata().put("_enriched", true);
        msg.getMetadata().put("_originalQuestion", "总结一下");
        msg.getMetadata().put("files", List.of(Map.of("fileName", "test.pdf")));
        state.contextMutable().add(msg);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        cleanableStore.save("default", "dialogue-1", "agent_state", state);

        verify(sessionRepository).save(entityCaptor.capture());
        String savedJson = entityCaptor.getValue().getStateJson();

        assertTrue(savedJson.contains("总结一下"));
        assertTrue(savedJson.contains("test.pdf"));
    }

    @Test
    void cleanable_save_ShouldNotModifyNonEnrichedMessages() {
        CleanablePgAgentStateStore cleanableStore = new CleanablePgAgentStateStore(sessionRepository);
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();

        UserMessage msg = new UserMessage("普通用户消息，没有_enriched标记");
        state.contextMutable().add(msg);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        cleanableStore.save("default", "dialogue-1", "agent_state", state);

        verify(sessionRepository).save(entityCaptor.capture());
        String savedJson = entityCaptor.getValue().getStateJson();

        assertTrue(savedJson.contains("普通用户消息，没有_enriched标记"));
    }

    @Test
    void cleanable_save_ShouldFallbackToTextExtraction() {
        CleanablePgAgentStateStore cleanableStore = new CleanablePgAgentStateStore(sessionRepository);
        AgentState state = AgentState.builder().sessionId("dialogue-1").build();

        // _enriched=true but no _originalQuestion — should extract from text
        UserMessage msg = new UserMessage("请参考以下资料来回答问题。\n资料内容：some content\n\n问题：这个问题从文本中提取");
        msg.getMetadata().put("_enriched", true);
        // no _originalQuestion
        state.contextMutable().add(msg);

        when(sessionRepository.findBySessionId("dialogue-1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        cleanableStore.save("default", "dialogue-1", "agent_state", state);

        verify(sessionRepository).save(entityCaptor.capture());
        String savedJson = entityCaptor.getValue().getStateJson();

        assertTrue(savedJson.contains("这个问题从文本中提取"));
        assertFalse(savedJson.contains("some content"));
    }
}
