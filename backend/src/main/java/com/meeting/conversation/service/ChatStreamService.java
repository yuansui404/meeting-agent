package com.meeting.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.FileMetadata;
import com.meeting.common.SseEventTypes.SseEventType;
import com.meeting.common.SseEventTypes.SseToolResultEvent;
import com.meeting.conversation.model.AgentResponseMetadata;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
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
    private final SessionService sessionService;

    /**
     * 执行流式对话：agent.streamEvents() → SSE 事件流 → 持久化。
     * 前端断开只停止推流，大模型继续生成完毕并持久化。
     */
    public void stream(SseEmitter emitter, Long dialogueId, UserMessage msg,
                       RuntimeContext ctx, String originalQuestion, List<FileMetadata> messageFiles) {
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();
        Map<String, AgentResponseMetadata.ToolCallRecord> toolCalls = new LinkedHashMap<>();
        AtomicBoolean disconnected = new AtomicBoolean(false);

        // 注册SSE回调
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> {
            disconnected.set(true);
            try {
                emitter.completeWithError(new TimeoutException("SSE emitter timed out"));
            } catch (Exception ignored) {
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                agent.streamEvents(msg, ctx).subscribe(
                        event -> handleEvent(event, fullResponse, thinkingText, toolCalls, emitter, disconnected),
                        error -> handleError(error, emitter, disconnected, dialogueId, originalQuestion, messageFiles),
                        () -> handleComplete(emitter, disconnected, dialogueId, fullResponse, thinkingText, toolCalls, originalQuestion, messageFiles)
                );
            } catch (Exception e) {
                // streamEvents抛异常处理，与 subscribe 的互补
                log.error("Failed to subscribe to agent stream for dialogue {}", dialogueId, e);
                handleError(e, emitter, disconnected, dialogueId, originalQuestion, messageFiles);
            }
        });
    }

    private void handleEvent(AgentEvent event, StringBuilder fullResponse,
                             StringBuilder thinkingText, Map<String, AgentResponseMetadata.ToolCallRecord> toolCalls,
                             SseEmitter emitter, AtomicBoolean disconnected) {
        try {
            switch (event.getType()) {
                // AI 正在生成的文字（逐字）
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
                // AI 的思考过程
                case THINKING_BLOCK_DELTA -> {
                    String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                    if (delta != null && !delta.isEmpty()) {
                        thinkingText.append(delta);
                        if (!disconnected.get()) {
                            emitter.send(SseEmitter.event()
                                    .name(SseEventType.THINKING_DELTA.toString()).data(delta));
                        }
                    }
                }
                //  AI 开始调用工具（如 RAG 搜索）
                case TOOL_CALL_START -> {
                    ToolCallStartEvent e = (ToolCallStartEvent) event;
                    toolCalls.put(e.getToolCallId(), new AgentResponseMetadata.ToolCallRecord(
                            e.getToolCallId(), e.getToolCallName(), ""));
                    if (!disconnected.get()) {
                        String json = objectMapper.writeValueAsString(
                                new SseToolResultEvent("start", e.getToolCallId(), e.getToolCallName(), null));
                        emitter.send(SseEmitter.event()
                                .name(SseEventType.TOOL_CALL.toString()).data(json));
                    }
                }
                // 工具返回结果的增量
                case TOOL_RESULT_TEXT_DELTA -> {
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    toolCalls.computeIfPresent(e.getToolCallId(),
                            (id, tc) -> new AgentResponseMetadata.ToolCallRecord(tc.id(), tc.name(), tc.result() + e.getDelta()));
                    if (!disconnected.get()) {
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
                             Long dialogueId,
                             String originalQuestion, List<FileMetadata> messageFiles) {
        String errorMsg = error instanceof RuntimeException && error.getCause() != null
                ? error.getCause().getMessage() : error.getMessage();
        log.error("Stream error for dialogue {}: {}", dialogueId, errorMsg);

        String errorResponse = "抱歉，处理您的请求时遇到错误：" + (errorMsg != null ? errorMsg : "未知错误");
        dialoguePersistenceService.persistErrorDialogue(dialogueId, originalQuestion, messageFiles, errorResponse);

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
                                Long dialogueId, StringBuilder fullResponse,
                                StringBuilder thinkingText, Map<String, AgentResponseMetadata.ToolCallRecord> toolCalls,
                                String originalQuestion, List<FileMetadata> messageFiles) {
        try {
            dialoguePersistenceService.persistUserMessage(dialogueId, originalQuestion, messageFiles);
            dialoguePersistenceService.persistAssistantResponse(dialogueId, fullResponse.toString(),
                    thinkingText.toString(), new ArrayList<>(toolCalls.values()), null);
            sessionService.autoTitleIfNeeded(dialogueId, originalQuestion);
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
