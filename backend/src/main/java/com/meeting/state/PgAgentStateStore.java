package com.meeting.state;

import com.meeting.entity.SessionEntity;
import com.meeting.repository.SessionRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class PgAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(PgAgentStateStore.class);
    private static final String AGENT_STATE_KEY = "agent_state";

    private final SessionRepository sessionRepository;

    public PgAgentStateStore(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public void save(String userId, String sessionId, String key, State state) {
        if (!AGENT_STATE_KEY.equals(key)) return; // only persist agent_state

        String json = state instanceof AgentState as ? as.toJson() : "";
        SessionEntity entity = sessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    SessionEntity e = new SessionEntity();
                    e.setSessionId(sessionId);
                    return e;
                });
        entity.setStateJson(json);
        entity.setMessageCount(state instanceof AgentState as
                ? as.getContext().size() : 0);
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        sessionRepository.save(entity);
    }

    @Override
    @Transactional
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        for (State s : states) {
            save(userId, sessionId, key, s);
        }
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        if (!AGENT_STATE_KEY.equals(key)) return Optional.empty();
        return sessionRepository.findBySessionId(sessionId)
                .filter(e -> e.getStateJson() != null && !e.getStateJson().isBlank())
                .map(e -> {
                    try {
                        @SuppressWarnings("unchecked")
                        T state = (T) AgentState.fromJsonString(e.getStateJson());
                        return state;
                    } catch (Exception ex) {
                        log.warn("Failed to deserialize AgentState for session {}: {}", sessionId, ex.getMessage());
                        return null;
                    }
                });
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        return get(userId, sessionId, key, type)
                .map(List::of)
                .orElseGet(List::of);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return sessionRepository.existsBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void delete(String userId, String sessionId) {
        sessionRepository.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void delete(String userId, String sessionId, String key) {
        // We store all states under a single JSONB, so ignore key
        delete(userId, sessionId);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return new HashSet<>(sessionRepository.findAll().stream()
                .map(SessionEntity::getSessionId)
                .toList());
    }
}
