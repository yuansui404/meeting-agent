package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.document.service.DocumentTextExtractor;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.state.PgAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class ChatService {
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit for file reading

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");
    @Qualifier("meetingAssistantAgent") private final HarnessAgent agent;
    private final PgAgentStateStore pgAgentStateStore;
    private final DialogueMessageRepository dialogueMessageRepository;
    private final com.meeting.conversation.repository.SessionRepository sessionRepository;
    @Qualifier("llmTaskExecutor") private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        taskExecutor.execute(() -> {
            try {
                List<Long> messageFileIds = parseFileIdsFromMetadata(metadata);
                List<Map<String, Object>> messageFiles = parseFilesFromMetadata(metadata);
                streamChatWithDeepSeek(dialogueId, userMessage, messageFileIds, messageFiles, emitter);
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                        List<Long> messageFileIds,
                                        List<Map<String, Object>> messageFiles,
                                        SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            String fileContext = buildFileTextContext(dialogueId, messageFileIds, messageFiles);
            String enriched = buildEnrichedMessage(fileContext, userMessage);
            UserMessage msg = new UserMessage(enriched);

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("dialogue-" + dialogueId)
                    .build();

            // Auto-approve all tool calls (bypass permission confirmation)
            agent.setPermissionMode(ctx, io.agentscope.core.permission.PermissionMode.BYPASS);

            final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            final AtomicBoolean invokedTools = new AtomicBoolean(false);
            final AtomicBoolean subagentActive = new AtomicBoolean(false);
            agent.streamEvents(msg, ctx)
                    .doOnNext(event -> {
                        // Track subagent lifecycle — suppress all subagent output
                        if (event instanceof AgentStartEvent) {
                            subagentActive.set(true);
                        } else if (event instanceof AgentEndEvent) {
                            subagentActive.set(false);
                        }
                        if (event instanceof ToolCallStartEvent e) {
                            invokedTools.set(true);
                        }
                        if (!clientDisconnected.get()) {
                            try {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    if (subagentActive.get()) return; // subagent text → skip
                                    String delta = e.getDelta();
                                    fullResponse.append(delta);
                                    emitter.send(SseEmitter.event().data(delta));
                                } else if (event instanceof ThinkingBlockDeltaEvent e) {
                                    if (subagentActive.get()) return; // subagent thinking → skip
                                    String delta = e.getDelta();
                                    if (delta != null) {
                                        delta = delta.replaceAll("(?i)exit\\s*code:?\\s*\\d+", "").trim();
                                        if (!delta.isEmpty()) {
                                            emitter.send(SseEmitter.event().name("thinking").data(delta));
                                        }
                                    }
                                } else if (event instanceof ToolCallStartEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_call")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "action", "start",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolCallEndEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_call")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "action", "end",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolResultStartEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "action", "start",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolResultTextDeltaEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "action", "delta",
                                                    "id", e.getToolCallId(),
                                                    "delta", e.getDelta()
                                            ))));
                                } else if (event instanceof ToolResultEndEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "action", "end",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                }
                            } catch (IOException | IllegalStateException ex) {
                                clientDisconnected.set(true);
                                log.info("Client disconnected during DeepSeek stream: {}", ex.getMessage());
                            } catch (Exception ex) {
                                log.warn("Failed to serialize SSE event: {}", ex.getMessage());
                            }
                        }
                    })
                    .blockLast();

            String responseText = fullResponse.toString();

            cleanUpUserMessageInState(dialogueId, userMessage, messageFileIds, messageFiles, responseText);
            persistDialogueMessages(dialogueId, userMessage, responseText, messageFiles);
            completeEmitter(emitter);
        } catch (Exception e) {
            handleStreamError(emitter, e);
        }
    }

    private void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            String msg = e instanceof RuntimeException && e.getCause() != null
                    ? e.getCause().getMessage()
                    : e.getMessage();
            emitter.send(SseEmitter.event().name("error").data(msg != null ? msg : "Unknown error"));
        } catch (IOException ignored) {
        }
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
        }
    }

    private String buildEnrichedMessage(String fileContext, String userMessage) {
        if (fileContext == null || fileContext.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请参考以下资料来回答问题。\n");
        sb.append("要求：\n");
        sb.append("1. 答案必须直接引用资料中的原文，不得添加资料中没有的信息\n");
        sb.append("2. 「本次提交的文件」是用户当前关注的重点，优先参考\n");
        sb.append("3. 「对话历史中的文件」仅在用户提及相关内容时参考\n");
        sb.append("\n资料内容：\n").append(fileContext);
        sb.append("\n\n问题：").append(userMessage);
        return sb.toString();
    }

    /**
     * Parse fileIds array from metadata JSON string.
     * Metadata format: {"fileIds":[1,2,3]}
     * Returns empty list if no fileIds found or parse error (backward compatible).
     */
    private List<Long> parseFileIdsFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return List.of();
        try {
            Map<String, Object> map = objectMapper.readValue(metadata, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object fileIds = map.get("fileIds");
            if (fileIds instanceof List<?> list) {
                return list.stream()
                        .filter(Objects::nonNull)
                        .map(item -> item instanceof Number n ? n.longValue() : Long.parseLong(item.toString()))
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to parse fileIds from metadata: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> parseFilesFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return List.of();
        try {
            Map<String, Object> map = objectMapper.readValue(metadata, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object files = map.get("files");
            if (files instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) item;
                        result.add(m);
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to parse files from metadata: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Build file context for AI from file metadata in state_json.
     */
    private String buildFileTextContext(Long dialogueId, List<Long> messageFileIds, List<Map<String, Object>> messageFiles) {
        StringBuilder sb = new StringBuilder();

        // New-style files from metadata (disk paths)
        if (messageFiles != null && !messageFiles.isEmpty()) {
            sb.append("【本次提交的文件 — 请重点参考这些文件回答】\n");
            for (Map<String, Object> fm : messageFiles) {
                appendFileContentFromMeta(sb, fm);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Read file content from disk path stored in file metadata.
     * For audio/video files, reads the sidecar transcription file if available.
     */
    private void appendFileContentFromMeta(StringBuilder sb, Map<String, Object> fm) {
        String name = (String) fm.get("fileName");
        String path = (String) fm.get("filePath");
        String ext = (String) fm.get("ext");
        if (name == null || path == null || ext == null) return;
        if (IMAGE_FORMATS.contains(ext.toLowerCase())) return;

        Number sizeNum = (Number) fm.get("fileSize");
        long size = sizeNum != null ? sizeNum.longValue() : 0;
        if (size > MAX_FILE_SIZE) {
            sb.append("【来源：").append(name).append("】（文件过大，跳过内容提取）\n");
            return;
        }

        String content = extractFileContent(Path.of(path), ext.toLowerCase());

        // For audio/video files, check for sidecar transcription file
        if (content == null && FileProcessingService.isTranscribable(ext.toLowerCase())) {
            Path transcriptionPath = Path.of(path + ".transcription.md");
            if (Files.exists(transcriptionPath)) {
                try {
                    content = Files.readString(transcriptionPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Failed to read transcription sidecar file: {}", transcriptionPath);
                }
            }
        }

        if (content != null && !content.isBlank()) {
            String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
            sb.append("【来源：").append(name).append("】\n").append(preview).append("\n\n");
        } else {
            sb.append("【来源：").append(name).append("】（无法提取文字内容）\n");
        }
    }

    private String extractFileContent(Path filePath, String ext) {
        try {
            if (TEXT_FORMATS.contains(ext)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            if (DOC_FORMATS.contains(ext)) {
                return DocumentTextExtractor.extractText(filePath, ext);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanUpUserMessageInState(Long dialogueId, String userMessage,
                                           List<Long> messageFileIds,
                                           List<Map<String, Object>> messageFiles,
                                           String responseText) {
        try {
            AgentState st = pgAgentStateStore.get("default", "dialogue-" + dialogueId,
                    "agent_state", AgentState.class).orElse(null);
            if (st != null) {
                var context = st.contextMutable();
                for (int i = context.size() - 1; i >= 0; i--) {
                    if (context.get(i).getRole() == MsgRole.USER) {
                        context.set(i, buildCleanUserMessage(userMessage, messageFileIds, messageFiles));
                        break;
                    }
                }
                pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", st);
            } else {
                AgentState ns = AgentState.builder().sessionId("dialogue-" + dialogueId).build();
                ns.contextMutable().add(buildCleanUserMessage(userMessage, messageFileIds, messageFiles));
                if (!responseText.isEmpty()) {
                    ns.contextMutable().add(new AssistantMessage(responseText));
                }
                pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", ns);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up user message for dialogue {}: {}", dialogueId, e.getMessage());
        }
    }

    private UserMessage buildCleanUserMessage(String userMessage,
                                              List<Long> messageFileIds,
                                              List<Map<String, Object>> messageFiles) {
        UserMessage.Builder builder = UserMessage.builder().textContent(userMessage);
        Map<String, Object> meta = new HashMap<>();
        if (messageFileIds != null && !messageFileIds.isEmpty()) {
            meta.put("fileIds", messageFileIds);
        }
        if (messageFiles != null && !messageFiles.isEmpty()) {
            meta.put("files", messageFiles);
        }
        if (!meta.isEmpty()) {
            builder.metadata(meta);
        }
        return builder.build();
    }

    private void persistDialogueMessages(Long dialogueId, String userMessage,
                                         String assistantResponse,
                                         List<Map<String, Object>> messageFiles) {
        try {
            txTemplate.executeWithoutResult(status -> {
                try {
                    String filesJson = messageFiles != null && !messageFiles.isEmpty()
                            ? objectMapper.writeValueAsString(messageFiles) : null;
                    DialogueMessageEntity dmUser = new DialogueMessageEntity();
                    dmUser.setDialogueId(dialogueId);
                    dmUser.setRole("user");
                    dmUser.setContent(userMessage);
                    dmUser.setMessageType("text");
                    dmUser.setFiles(filesJson);
                    dialogueMessageRepository.save(dmUser);

                    if (!assistantResponse.isEmpty()) {
                        DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                        asstMsg.setDialogueId(dialogueId);
                        asstMsg.setRole("assistant");
                        asstMsg.setContent(assistantResponse);
                        asstMsg.setMessageType("text");
                        dialogueMessageRepository.save(asstMsg);
                    }

                    // Update session updatedAt so dialogue list shows correct time
                    sessionRepository.findById(dialogueId).ifPresent(session -> {
                        session.setUpdatedAt(java.time.LocalDateTime.now());
                        sessionRepository.save(session);
                    });
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to persist dialogue_messages for dialogue {}: {}", dialogueId, e.getMessage());
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException | IllegalStateException ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

}
