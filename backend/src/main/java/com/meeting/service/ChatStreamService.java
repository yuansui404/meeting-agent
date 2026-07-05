package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.service.SseEventTypes.SseEventType;
import com.meeting.service.SseEventTypes.SseToolEvent;
import com.meeting.service.SseEventTypes.SseToolResultEvent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式输出控制。负责 agent.stream() 的事件分发和 SSE 发送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    @Qualifier("meetingAssistantAgent") private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final DialoguePersistenceService dialoguePersistenceService;

    // ========== 事件分发：按 EventType 路由 ==========
    @FunctionalInterface
    private interface EventHandler {
        void handle(Event event, SseEmitter emitter, StringBuilder fullResponse) throws IOException;
    }

    private final Map<EventType, EventHandler> handlers = new EnumMap<>(EventType.class) {{
        put(EventType.REASONING,    ChatStreamService.this::collectText);
        put(EventType.AGENT_RESULT, ChatStreamService.this::streamAndPersist);
        put(EventType.TOOL_RESULT,  ChatStreamService.this::forwardToolResult);
    }};

    /**
     * 执行流式对话：构建 UserMessage → agent.stream() → SSE 事件转发 → 持久化。
     */
    public void stream(Long dialogueId, String enrichedMessage, String originalQuestion,
                       List<Map<String, Object>> messageFiles, SseEmitter emitter,
                       CancellationToken cancelToken) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            UserMessage msg = new UserMessage(enrichedMessage);
            msg.getMetadata().put("_enriched", true);
            msg.getMetadata().put("_originalQuestion", originalQuestion);
            if (messageFiles != null && !messageFiles.isEmpty()) {
                msg.getMetadata().put("files", messageFiles);
            }

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("dialogue-" + dialogueId)
                    .build();

            cancelToken.setCancelAction(() -> {
                log.info("Interrupting agent for dialogue {}", dialogueId);
                agent.getDelegate().interrupt(ctx);
            });

            final AtomicBoolean clientDisconnected = new AtomicBoolean(false);

            agent.stream(msg, ctx)
                    .takeUntil(event -> cancelToken.isCancelled())
                    .doOnNext(event -> {
                        if (!clientDisconnected.get()) {
                            try {
                                forwardEvent(event, emitter, fullResponse, clientDisconnected);
                            } catch (IOException | IllegalStateException ex) {
                                clientDisconnected.set(true);
                                log.info("Client disconnected during stream: {}", ex.getMessage());
                            } catch (Exception ex) {
                                log.warn("Failed to serialize SSE event: {}", ex.getMessage());
                            }
                        }
                    })
                    .blockLast();

            if (!cancelToken.isCancelled()) {
                dialoguePersistenceService.persist(dialogueId, originalQuestion,
                        fullResponse.toString(), messageFiles);
                completeEmitter(emitter);
            } else {
                log.info("Stream interrupted for dialogue {}, skipping persistence", dialogueId);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (!cancelToken.isCancelled()) {
                handleStreamError(emitter, e);
            } else {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    private void forwardEvent(Event event, SseEmitter emitter,
                              StringBuilder fullResponse, AtomicBoolean clientDisconnected) throws IOException {
        log.debug("SSE event: type={}", event.getType());
        EventHandler handler = handlers.get(event.getType());
        if (handler != null) {
            handler.handle(event, emitter, fullResponse);
        }
    }

    // ========== 各事件处理器 ==========

    /**
     * 收集推理文本：记录到 fullResponse（用于持久化），不发送 SSE。
     */
    private void collectText(Event event, SseEmitter emitter,
                             StringBuilder fullResponse) {
        Msg message = event.getMessage();
        if (message == null) return;
        String text = message.getTextContent();
        if (text != null && !text.isEmpty()) {
            fullResponse.append(text);
        }
    }

    /**
     * 最终结果：发送 SSE + 覆盖 fullResponse（用于持久化）。
     */
    private void streamAndPersist(Event event, SseEmitter emitter,
                                  StringBuilder fullResponse) throws IOException {
        Msg message = event.getMessage();
        if (message == null) return;
        String text = message.getTextContent();
        if (text == null || text.isEmpty()) return;
        fullResponse.setLength(0);
        fullResponse.append(text);
        emitter.send(SseEmitter.event()
                .name(SseEventType.TEXT_DELTA.name()).data(text));
    }

    /**
     * 工具结果转发：从 Event.message 中提取文本，发送 SSE TOOL_RESULT 事件。
     */
    private void forwardToolResult(Event event, SseEmitter emitter,
                                   StringBuilder fullResponse) throws IOException {
        Msg message = event.getMessage();
        if (message == null) return;
        String text = message.getTextContent();
        if (text == null || text.isEmpty()) return;
        emitter.send(SseEmitter.event()
                .name(SseEventType.TOOL_RESULT.name())
                .data(objectMapper.writeValueAsString(
                        new SseToolResultEvent("delta", null, null, text))));
    }

    // ========== 辅助方法 ==========

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
