package com.meeting.state;

import com.meeting.common.EnrichedMessageConstants;
import com.meeting.conversation.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 继承 PgAgentStateStore，在 save() 时自动清洗 enriched UserMessage。
 * 解决 ChatService.cleanUpUserMessageInState 的读-改-写竞态条件。
 */
@Slf4j
@Component
public class CleanablePgAgentStateStore extends PgAgentStateStore {

    public CleanablePgAgentStateStore(SessionRepository sessionRepository) {
        super(sessionRepository);
    }

    @Override
    @Transactional
    public void save(String userId, String sessionId, String key, State state) {
        if (state instanceof AgentState as) {
            cleanEnrichedMessages(as);
        }
        super.save(userId, sessionId, key, state);
    }

    private void cleanEnrichedMessages(AgentState state) {
        List<Msg> original = state.contextMutable();
        List<Msg> copy = new ArrayList<>(original);

        for (int i = 0; i < copy.size(); i++) {
            Msg msg = copy.get(i);
            if (msg.getRole() != MsgRole.USER) continue;
            Map<String, Object> meta = msg.getMetadata();
            if (meta == null || !Boolean.TRUE.equals(meta.get("_enriched"))) continue;

            String originalQuestion = (String) meta.get("_originalQuestion");
            if (originalQuestion == null) {
                originalQuestion = extractQuestionFromEnrichedText(msg.getTextContent());
            }
            if (originalQuestion == null) {
                log.warn("Cannot extract original question from enriched message at index {}, skipping", i);
                continue;
            }

            UserMessage.Builder builder = UserMessage.builder().textContent(originalQuestion);
            Map<String, Object> cleanMeta = new HashMap<>();
            Object files = meta.get("files");
            if (files != null) cleanMeta.put("files", files);
            if (!cleanMeta.isEmpty()) builder.metadata(cleanMeta);
            copy.set(i, builder.build());

            log.debug("Cleaned enriched message at index {}, question length={}", i, originalQuestion.length());
        }

        original.clear();
        original.addAll(copy);
    }

    private String extractQuestionFromEnrichedText(String text) {
        if (text == null || !text.startsWith(EnrichedMessageConstants.PREFIX)) return null;
        int idx = text.lastIndexOf(EnrichedMessageConstants.QUESTION_DELIMITER);
        if (idx >= 0) {
            return text.substring(idx + EnrichedMessageConstants.QUESTION_DELIMITER.length());
        }
        return null;
    }
}
