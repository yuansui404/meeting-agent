package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.service.SseEventTypes.SseEventType;
import com.meeting.service.SseEventTypes.SseToolEvent;
import com.meeting.service.SseEventTypes.SseToolResultEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式输出控制。负责 agent.streamEvents() 的事件分发和 SSE 发送。
 * 从 ChatService 中抽取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    @Qualifier("meetingAssistantAgent") private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final DialoguePersistenceService dialoguePersistenceService;

    /**
     * 执行流式对话：构建 UserMessage → agent.streamEvents() → SSE 事件转发 → 持久化。
     *
     * @param dialogueId      对话 ID
     * @param enrichedMessage 带文件上下文的完整 prompt（已由 FileContextBuilder 构建）
     * @param originalQuestion 用户原始问题（用于持久化）
     * @param messageFiles    文件元数据（用于持久化）
     */
    public void stream(Long dialogueId, String enrichedMessage, String originalQuestion,
                       List<Map<String, Object>> messageFiles, SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            UserMessage msg = new UserMessage(enrichedMessage);
            // 添加 metadata 标记，供 CleanablePgAgentStateStore 清洗
            msg.getMetadata().put("_enriched", true);
            msg.getMetadata().put("_originalQuestion", originalQuestion);
            if (messageFiles != null && !messageFiles.isEmpty()) {
                msg.getMetadata().put("files", messageFiles);
            }

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("dialogue-" + dialogueId)
                    .build();

            final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            final AtomicBoolean subagentActive = new AtomicBoolean(false);

            agent.streamEvents(msg, ctx)
                    .doOnNext(event -> {
                        if (event instanceof AgentStartEvent) {
                            subagentActive.set(true);
                        } else if (event instanceof AgentEndEvent) {
                            subagentActive.set(false);
                        }
                        if (!clientDisconnected.get()) {
                            try {
                                forwardEvent(emitter, event, fullResponse, subagentActive, clientDisconnected);
                            } catch (IOException | IllegalStateException ex) {
                                clientDisconnected.set(true);
                                log.info("Client disconnected during stream: {}", ex.getMessage());
                            } catch (Exception ex) {
                                log.warn("Failed to serialize SSE event: {}", ex.getMessage());
                            }
                        }
                    })
                    .blockLast();

            dialoguePersistenceService.persist(dialogueId, originalQuestion,
                    fullResponse.toString(), messageFiles);
            completeEmitter(emitter);
        } catch (Exception e) {
            handleStreamError(emitter, e);
        }
    }

    private void forwardEvent(SseEmitter emitter, Object event,
                              StringBuilder fullResponse, AtomicBoolean subagentActive,
                              AtomicBoolean clientDisconnected) throws IOException {
        if (event instanceof TextBlockDeltaEvent e) {
            if (subagentActive.get()) return;
            String delta = e.getDelta();
            fullResponse.append(delta);
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TEXT_DELTA.name()).data(delta));

        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            if (subagentActive.get()) return;
            String delta = e.getDelta();
            if (delta != null) {
                delta = delta.replaceAll("(?i)exit\\s*code:?\\s*\\d+", "").trim();
                if (!delta.isEmpty()) {
                    emitter.send(SseEmitter.event()
                            .name(SseEventType.THINKING_DELTA.name()).data(delta));
                }
            }

        } else if (event instanceof ToolCallStartEvent e) {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_CALL.name())
                    .data(objectMapper.writeValueAsString(
                            new SseToolEvent("start", e.getToolCallId(), e.getToolCallName()))));

        } else if (event instanceof ToolCallEndEvent e) {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_CALL.name())
                    .data(objectMapper.writeValueAsString(
                            new SseToolEvent("end", e.getToolCallId(), e.getToolCallName()))));

        } else if (event instanceof ToolResultStartEvent e) {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_RESULT.name())
                    .data(objectMapper.writeValueAsString(
                            new SseToolResultEvent("start", e.getToolCallId(), e.getToolCallName(), null))));

        } else if (event instanceof ToolResultTextDeltaEvent e) {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_RESULT.name())
                    .data(objectMapper.writeValueAsString(
                            new SseToolResultEvent("delta", e.getToolCallId(), e.getToolCallName(), e.getDelta()))));

        } else if (event instanceof ToolResultEndEvent e) {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_RESULT.name())
                    .data(objectMapper.writeValueAsString(
                            new SseToolResultEvent("end", e.getToolCallId(), e.getToolCallName(), null))));
        }
    }

    private void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            String msg = e instanceof RuntimeException && e.getCause() != null
                    ? e.getCause().getMessage()
                    : e.getMessage();
            emitter.send(SseEmitter.event()
                    .name(SseEventType.ERROR.name()).data(msg != null ? msg : "Unknown error"));
        } catch (IOException ignored) {
        }
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(SseEventType.DONE.name()).data(""));
        } catch (IOException | IllegalStateException ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
