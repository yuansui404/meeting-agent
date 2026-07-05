package com.meeting.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.FileMetadata;
import com.meeting.common.SseEventTypes.SseEventType;
import com.meeting.common.SseEventTypes.SseToolResultEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式输出控制。负责 agent.streamEvents() 的事件分发和 SSE 发送。
 * 使用虚拟线程实现异步，AtomicBoolean 实现前端断开与后端生成的生命周期解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    @Qualifier("meetingAssistantAgent") private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final DialoguePersistenceService dialoguePersistenceService;

    /**
     * 执行流式对话：构建 UserMessage → agent.streamEvents() → SSE 事件流 → 持久化。
     * 前端断开只停止推流，大模型继续生成完毕并持久化。
     */
    public void stream(SseEmitter emitter, Long dialogueId, String enrichedMessage,
                       String originalQuestion, List<FileMetadata> messageFiles) {
        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean disconnected = new AtomicBoolean(false);

        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));

        UserMessage msg = new UserMessage(enrichedMessage);
        msg.getMetadata().put("_enriched", true);
        msg.getMetadata().put("_originalQuestion", originalQuestion);
        if (messageFiles != null && !messageFiles.isEmpty()) {
            msg.getMetadata().put("files", messageFiles);
        }

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("dialogue-" + dialogueId)
                .build();

        Thread.ofVirtual().start(() -> {
            try {
                agent.streamEvents(msg, ctx).subscribe(
                        event -> handleEvent(event, fullResponse, emitter, disconnected),
                        error -> handleError(error, emitter, disconnected, dialogueId,
                                originalQuestion, fullResponse, messageFiles),
                        () -> handleComplete(emitter, disconnected, dialogueId,
                                originalQuestion, fullResponse, messageFiles)
                );
            } catch (Exception e) {
                log.error("Failed to subscribe to agent stream for dialogue {}", dialogueId, e);
                handleError(e, emitter, disconnected, dialogueId,
                        originalQuestion, fullResponse, messageFiles);
            }
        });
    }

    private void handleEvent(AgentEvent event, StringBuilder fullResponse,
                             SseEmitter emitter, AtomicBoolean disconnected) {
        try {
            switch (event.getType()) {
                case TEXT_BLOCK_DELTA -> {
                    String delta = ((TextBlockDeltaEvent) event).getDelta();
                    if (delta != null && !delta.isEmpty()) {
                        fullResponse.append(delta);
                        if (!disconnected.get()) {
                            emitter.send(SseEmitter.event()
                                    .name(SseEventType.TEXT_DELTA.toString()).data(delta));
                        }
                    }
                }
                case THINKING_BLOCK_DELTA -> {
                    if (!disconnected.get()) {
                        String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                        if (delta != null && !delta.isEmpty()) {
                            emitter.send(SseEmitter.event()
                                    .name(SseEventType.THINKING_DELTA.toString()).data(delta));
                        }
                    }
                }
                case TOOL_CALL_START -> {
                    if (!disconnected.get()) {
                        ToolCallStartEvent e = (ToolCallStartEvent) event;
                        String json = objectMapper.writeValueAsString(
                                new SseToolResultEvent("start", e.getToolCallId(), e.getToolCallName(), null));
                        emitter.send(SseEmitter.event()
                                .name(SseEventType.TOOL_CALL.toString()).data(json));
                    }
                }
                case TOOL_RESULT_TEXT_DELTA -> {
                    if (!disconnected.get()) {
                        ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                        String json = objectMapper.writeValueAsString(
                                new SseToolResultEvent("delta", e.getToolCallId(), e.getToolCallName(), e.getDelta()));
                        emitter.send(SseEmitter.event()
                                .name(SseEventType.TOOL_RESULT.toString()).data(json));
                    }
                }
                case AGENT_RESULT -> {
                    // 用最终完整结果替换 fullResponse
                    String text = ((AgentResultEvent) event).getResult().getTextContent();
                    if (text != null && !text.isEmpty()) {
                        fullResponse.setLength(0);
                        fullResponse.append(text);
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            if (!disconnected.get()) {
                log.warn("Failed to send SSE event: {}", e.getMessage());
            }
        }
    }

    private void handleError(Throwable error, SseEmitter emitter, AtomicBoolean disconnected,
                             Long dialogueId, String originalQuestion,
                             StringBuilder fullResponse, List<FileMetadata> messageFiles) {
        String errorMsg = error instanceof RuntimeException && error.getCause() != null
                ? error.getCause().getMessage() : error.getMessage();
        log.error("Stream error for dialogue {}: {}", dialogueId, errorMsg);

        try {
            dialoguePersistenceService.persist(dialogueId, originalQuestion,
                    fullResponse.toString(), messageFiles);
        } catch (Exception e) {
            log.warn("Failed to persist after error for dialogue {}: {}", dialogueId, e.getMessage());
        }

        if (!disconnected.get()) {
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.ERROR.toString())
                        .data(errorMsg != null ? errorMsg : "Unknown error"));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleComplete(SseEmitter emitter, AtomicBoolean disconnected,
                                Long dialogueId, String originalQuestion,
                                StringBuilder fullResponse, List<FileMetadata> messageFiles) {
        try {
            dialoguePersistenceService.persist(dialogueId, originalQuestion,
                    fullResponse.toString(), messageFiles);
        } catch (Exception e) {
            log.warn("Failed to persist for dialogue {}: {}", dialogueId, e.getMessage());
        }

        if (!disconnected.get()) {
            try {
                emitter.send(SseEmitter.event().name(SseEventType.DONE.toString()).data(""));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to send done event for dialogue {}: {}", dialogueId, e.getMessage());
            }
        }
    }
}
