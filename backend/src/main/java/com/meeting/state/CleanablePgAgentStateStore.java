package com.meeting.state;

import com.meeting.conversation.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 继承 PgAgentStateStore，在 save() 时自动清洗 enriched UserMessage。
 * 解决 ChatService.cleanUpUserMessageInState 的读-改-写竞态条件。
 */
@Slf4j
@Component
public class CleanablePgAgentStateStore extends PgAgentStateStore {

    private static final String ENRICHED_PREFIX = "请参考以下资料来回答问题。";
    private static final String QUESTION_DELIMITER = "\n\n问题：";

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
        List<Msg> context = state.contextMutable();
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.USER) continue;
            if (!Boolean.TRUE.equals(msg.getMetadata().get("_enriched"))) continue;

            String originalQuestion = (String) msg.getMetadata().get("_originalQuestion");
            if (originalQuestion == null) {
                originalQuestion = extractQuestionFromEnrichedText(msg.getTextContent());
            }
            if (originalQuestion == null) {
                log.warn("Cannot extract original question from enriched message at index {}, skipping", i);
                continue;
            }

            UserMessage.Builder builder = UserMessage.builder().textContent(originalQuestion);
            Map<String, Object> cleanMeta = new HashMap<>();
            Object fileIds = msg.getMetadata().get("fileIds");
            Object files = msg.getMetadata().get("files");
            if (fileIds != null) cleanMeta.put("fileIds", fileIds);
            if (files != null) cleanMeta.put("files", files);
            if (!cleanMeta.isEmpty()) builder.metadata(cleanMeta);
            context.set(i, builder.build());

            log.debug("Cleaned enriched message at index {}, question length={}", i, originalQuestion.length());
        }
    }

    private String extractQuestionFromEnrichedText(String text) {
        if (text == null || !text.startsWith(ENRICHED_PREFIX)) return null;
        int idx = text.lastIndexOf(QUESTION_DELIMITER);
        if (idx >= 0) {
            return text.substring(idx + QUESTION_DELIMITER.length());
        }
        return null;
    }
}
