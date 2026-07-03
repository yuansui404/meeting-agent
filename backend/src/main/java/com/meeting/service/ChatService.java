package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.config.AgentConfig;
import com.meeting.config.DeepSeekProperties;
import com.meeting.config.ZhiPuProperties;
import com.meeting.document.service.DocumentTextExtractor;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.state.PgAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.event.*;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIClient;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
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

@Service
@RefreshScope
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit for file reading

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    private final HarnessAgent agent;
    private final PgAgentStateStore pgAgentStateStore;
    private final DialogueMessageRepository dialogueMessageRepository;
    @Qualifier("llmTaskExecutor") private final TaskExecutor taskExecutor;
    private final OpenAIClient openAIClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final DeepSeekProperties deepSeekProps;
    private final ZhiPuProperties zhiPuProps;

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        taskExecutor.execute(() -> {
            try {
                // Parse file info from metadata
                List<Long> messageFileIds = parseFileIdsFromMetadata(metadata);
                List<Map<String, Object>> messageFiles = parseFilesFromMetadata(metadata);

                // Build file blocks (images for multimodal, others for text context)
                List<ContentBlock> fileBlocks = buildFileBlocks(dialogueId, messageFileIds, messageFiles);
                boolean hasImages = fileBlocks.stream().anyMatch(b -> b instanceof ImageBlock);

                // ZhiPu only for multimodal (images), DeepSeek for all text
                if (hasImages) {
                    streamChatWithZhipu(dialogueId, userMessage, fileBlocks, messageFileIds, messageFiles, emitter);
                } else {
                    streamChatWithDeepSeek(dialogueId, userMessage, fileBlocks, messageFileIds, messageFiles, emitter);
                }
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                        List<ContentBlock> fileBlocks,
                                        List<Long> messageFileIds,
                                        List<Map<String, Object>> messageFiles,
                                        SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            // Profile context removed from user message to avoid polluting saved state.
            // Agent can read profile via read_profile tool when needed.
            String fileContext = buildFileTextContext(dialogueId, messageFileIds, messageFiles);
            UserMessage msg;
            if (!fileBlocks.isEmpty()) {
                String enriched = buildEnrichedMessage(fileContext, userMessage);
                List<ContentBlock> blocks = new ArrayList<>();
                blocks.add(TextBlock.builder().text(enriched).build());
                blocks.addAll(fileBlocks);
                msg = new UserMessage(blocks);
            } else {
                String enriched = buildEnrichedMessage(fileContext, userMessage);
                msg = new UserMessage(enriched);
            }

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("dialogue-" + dialogueId)
                    .build();

            final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            final AtomicBoolean invokedTools = new AtomicBoolean(false);
            agent.streamEvents(msg, ctx)
                    .doOnNext(event -> {
                        if (event instanceof ToolCallStartEvent) {
                            invokedTools.set(true);
                        }
                        if (!clientDisconnected.get()) {
                            try {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    String delta = e.getDelta();
                                    fullResponse.append(delta);
                                    emitter.send(SseEmitter.event().data(delta));
                                } else if (event instanceof ThinkingBlockDeltaEvent e) {
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

            // Self-check: validate response when tools were invoked
            String responseText = fullResponse.toString();
            String finalResponse = responseText;
            if (invokedTools.get() && !clientDisconnected.get() && !responseText.isEmpty()) {
                try {
                    String corrected = performSelfCheck(userMessage, responseText);
                    if (corrected != null && !corrected.equals(responseText)) {
                        emitter.send(SseEmitter.event().name("corrected").data(corrected));
                        finalResponse = corrected;
                        // Update the agent state with corrected content
                        AgentState state = pgAgentStateStore.get("default", "dialogue-" + dialogueId,
                                "agent_state", AgentState.class).orElse(null);
                        if (state != null) {
                            var context = state.contextMutable();
                            for (int i = context.size() - 1; i >= 0; i--) {
                                if ("assistant".equals(context.get(i).getRole())) {
                                    context.set(i, new AssistantMessage(corrected));
                                    break;
                                }
                            }
                            pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", state);
                        }
                        log.info("Self-check corrected response for dialogue {}", dialogueId);
                    }
                } catch (Exception e) {
                    log.warn("Self-check failed for dialogue {}: {}", dialogueId, e.getMessage());
                }
            }

            cleanUpUserMessageInState(dialogueId, userMessage, messageFileIds, messageFiles, fullResponse.toString());
            persistDialogueMessages(dialogueId, userMessage, finalResponse, messageFiles);
            completeEmitter(emitter);
        } catch (Exception e) {
            handleStreamError(emitter, e);
        }
    }

    private void streamChatWithZhipu(Long dialogueId, String userMessage,
                                     List<ContentBlock> fileBlocks,
                                     List<Long> messageFileIds,
                                     List<Map<String, Object>> messageFiles,
                                     SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            String fileContext = buildFileTextContext(dialogueId, messageFileIds, messageFiles);
            String enriched = buildEnrichedMessage(fileContext, userMessage);

            // Build multimodal content parts
            List<Object> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text", enriched));
            for (ContentBlock block : fileBlocks) {
                if (block instanceof ImageBlock ib) {
                    Source source = ib.getSource();
                    if (source instanceof Base64Source b64) {
                        contentParts.add(Map.of(
                                "type", "image_url",
                                "image_url", Map.of("url",
                                        "data:" + b64.getMediaType() + ";base64," + b64.getData())
                        ));
                    }
                }
            }

            // Build messages using AgentScope DTOs
            OpenAIMessage sysMsg = new OpenAIMessage();
            sysMsg.setRole("system");
            sysMsg.setContent(AgentConfig.SYSTEM_PROMPT);


            OpenAIMessage userMsg = new OpenAIMessage();
            userMsg.setRole("user");
            userMsg.setContent(contentParts);

            OpenAIRequest request = OpenAIRequest.builder()
                    .model(zhiPuProps.getModel())
                    .messages(List.of(sysMsg, userMsg))
                    .stream(true)
                    .build();

            // Stream via OpenAIClient — ZhiPu uses /chat/completions (no /v1 prefix),
            // so override the default endpoint to empty and pass the full URL as baseUrl
            GenerateOptions opts = GenerateOptions.builder().endpointPath("").build();
            AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            openAIClient.stream(zhiPuProps.getApiKey(), zhiPuProps.getUrl() + "/chat/completions", request, opts)
                    .doOnNext(response -> {
                        if (response.isChunk()) {
                            OpenAIMessage delta = response.getFirstChoice().getDelta();
                            if (delta != null) {
                                String content = delta.getContentAsString();
                                if (content != null && !content.isEmpty()) {
                                    fullResponse.append(content);
                                    if (!clientDisconnected.get()) {
                                        try {
                                            emitter.send(SseEmitter.event().data(content));
                                        } catch (IOException | IllegalStateException ex) {
                                            clientDisconnected.set(true);
                                            log.info("Client disconnected during ZhiPu stream: {}", ex.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .blockLast();

            cleanUpUserMessageInState(dialogueId, userMessage, messageFileIds, messageFiles, fullResponse.toString());
            persistDialogueMessages(dialogueId, userMessage, fullResponse.toString(), messageFiles);
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
     * Self-check: validate response via non-streaming DeepSeek call.
     * Returns corrected text if issues found, or original text if none.
     */
    private String performSelfCheck(String userMessage, String responseText) {
        String checkPrompt = """
                你是一个AI助手回答质量检查员。检查以下回答的质量：

                检查要点：
                1. 回答是否准确、完整地回应了用户的问题
                2. 是否存在事实性错误或幻觉信息
                3. 是否存在错别字、语法错误或表达不清晰的地方
                4. 如果引用了外部信息，是否与问题相关

                用户问题：%s

                AI回答：%s

                如果回答有错误或需要改进，请给出修正后的完整版本。
                如果回答没有问题，请直接回复「无需修改」。
                """.formatted(userMessage, responseText);

        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepSeekProps.getModel())
                    .messages(List.of(
                            OpenAIMessage.builder().role("system")
                                    .content("你是一个严谨的回答质量检查员，检查回答是否存在事实错误、幻觉和表达问题。").build(),
                            OpenAIMessage.builder().role("user").content(checkPrompt).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(4096)
                    .build();

            OpenAIResponse response = openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request);
            String result = response.getFirstChoice().getMessage().getContentAsString();

            if (result == null || result.contains("无需修改") || result.contains("没有问题")) {
                return responseText;
            }

            log.info("Self-check found issues, providing corrected response");
            return result;
        } catch (Exception e) {
            log.warn("Self-check OpenAIClient call failed: {}", e.getMessage());
            return responseText;
        }
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

    private List<ContentBlock> buildFileBlocks(Long dialogueId, List<Long> messageFileIds, List<Map<String, Object>> messageFiles) {
        List<ContentBlock> blocks = new ArrayList<>();

        // New-style files from metadata
        if (messageFiles != null) {
            for (Map<String, Object> fm : messageFiles) {
                String ext = (String) fm.get("ext");
                if (ext == null || !IMAGE_FORMATS.contains(ext.toLowerCase())) continue;
                String path = (String) fm.get("filePath");
                if (path == null) continue;
                Path filePath = Path.of(path);
                if (!Files.exists(filePath)) continue;
                try {
                    if (Files.size(filePath) > MAX_FILE_SIZE) {
                        log.warn("Image too large, skipping: {} ({} bytes)", path, Files.size(filePath));
                        continue;
                    }
                    String mediaType = getImageMimeType(ext.toLowerCase());
                    String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
                    blocks.add(new ImageBlock(new Base64Source(mediaType, base64)));
                } catch (IOException e) {
                    log.warn("Failed to read image {}: {}", path, e.getMessage());
                }
            }
        }

        return blocks;
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

    private String getImageMimeType(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".svg" -> "image/svg+xml";
            default -> "image/png";
        };
    }
}
