package com.meeting.service;

import com.meeting.agent.ListMeetingsTool;
import com.meeting.agent.SearchKnowledgeBaseTool;
import com.meeting.agent.SearchMeetingTitlesTool;
import com.meeting.common.DocumentTextExtractor;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.DialogueRepository;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.message.*;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RefreshScope
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SYSTEM_PROMPT = """
            你是会议纪要智能助手，具备以下能力：

            ## 工具
            - search_knowledge_base — 搜索知识库中的会议内容，获取具体讨论、决定、与会人等信息
            - list_meetings — 浏览会议记录列表，查看有哪些会议
            - search_meeting_titles — 通过标题关键词搜索特定会议
            - tavily_search — 联网搜索实时信息（如最新政策、技术文档、外部资料等）
            - upload_to_knowledge_base — 将对话中的文件上传到知识库

            ## 要求
            - 回答简洁准确
            - 引用知识库内容时注明来源会议名称
            - 联网搜索结果需说明信息来源
            """;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit for file reading

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    private final HarnessAgent agent;
    private final DialogueService dialogueService;
    private final DialogueRepository dialogueRepository;
    private final UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool;
    private final SearchKnowledgeBaseTool searchKnowledgeBaseTool;
    private final ListMeetingsTool listMeetingsTool;
    private final SearchMeetingTitlesTool searchMeetingTitlesTool;
    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String deepseekApiKey;
    private final String deepseekApiUrl;
    private final String zhipuApiKey;
    private final String zhipuModel;
    private final String zhipuUrl;

    public ChatService(@Value("${deepseek.api-key:}") String apiKey,
                       @Value("${deepseek.model:deepseek-chat}") String modelName,
                       @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                       @Value("${zhipu.api-key:}") String zhipuApiKey,
                       @Value("${zhipu.model:glm-4v}") String zhipuModel,
                       @Value("${zhipu.url:https://open.bigmodel.cn/api/paas/v4}") String zhipuUrl,
                       @Value("${tavily.api-key:}") String tavilyApiKey,
                       DialogueService dialogueService,
                       DialogueRepository dialogueRepository,
                       MeetingMinutesRepository meetingRepository,
                       VectorizationService vectorizationService,
                       UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                       SearchKnowledgeBaseTool searchKnowledgeBaseTool,
                       ListMeetingsTool listMeetingsTool,
                       SearchMeetingTitlesTool searchMeetingTitlesTool) {
        this.dialogueService = dialogueService;
        this.dialogueRepository = dialogueRepository;
        this.meetingRepository = meetingRepository;
        this.vectorizationService = vectorizationService;
        this.uploadToKnowledgeBaseTool = uploadToKnowledgeBaseTool;
        this.searchKnowledgeBaseTool = searchKnowledgeBaseTool;
        this.listMeetingsTool = listMeetingsTool;
        this.searchMeetingTitlesTool = searchMeetingTitlesTool;
        this.deepseekApiKey = apiKey;
        this.deepseekApiUrl = apiUrl;
        this.zhipuApiKey = zhipuApiKey;
        this.zhipuModel = zhipuModel;
        this.zhipuUrl = zhipuUrl;

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(apiUrl)
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(uploadToKnowledgeBaseTool);
        toolkit.registerAgentTool(searchKnowledgeBaseTool);
        toolkit.registerAgentTool(listMeetingsTool);
        toolkit.registerAgentTool(searchMeetingTitlesTool);

        // Tavily MCP client (with graceful degradation)
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            try {
                McpClientWrapper tavilyClient = McpClientBuilder.create("tavily")
                        .stdioTransport("npx", List.of("tavily-mcp"), Map.of("TAVILY_API_KEY", tavilyApiKey))
                        .timeout(Duration.ofSeconds(30))
                        .buildSync();
                toolkit.registerMcpClient(tavilyClient).block();
                log.info("Tavily MCP client initialized successfully");
            } catch (Exception e) {
                log.warn("Tavily MCP client init failed (web search unavailable): {}", e.getMessage());
            }
        } else {
            log.info("TAVILY_API_KEY not configured, web search disabled");
        }

        this.agent = HarnessAgent.builder()
                .name("MeetingAssistant")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .disableMemoryHooks()
                .disableFilesystemTools()
                .build();
    }

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                // 1. Save user message to DB with file metadata
                dialogueService.addMessage(dialogueId, "user", userMessage, "text", metadata);

                // 2. Build file blocks (text + images)
                List<ContentBlock> fileBlocks = buildFileBlocks(dialogueId);
                boolean hasImages = fileBlocks.stream().anyMatch(b -> b instanceof ImageBlock);

                // 3. ZhiPu only for multimodal (images), DeepSeek for all text
                if (hasImages) {
                    streamChatWithZhipu(dialogueId, userMessage, fileBlocks, emitter);
                } else {
                    streamChatWithDeepSeek(dialogueId, userMessage, fileBlocks, emitter);
                }
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                        List<ContentBlock> fileBlocks,
                                        SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            UserMessage msg;
            String fileContext = buildFileTextContext(dialogueId);
            if (!fileBlocks.isEmpty()) {
                String enriched = buildEnrichedMessage(null, userMessage);
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

            final boolean[] clientDisconnected = {false};
            final boolean[] invokedTools = {false};
            agent.streamEvents(msg, ctx)
                    .doOnNext(event -> {
                        if (event instanceof ToolCallStartEvent) {
                            invokedTools[0] = true;
                        }
                        if (!clientDisconnected[0]) {
                            try {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    String delta = e.getDelta();
                                    fullResponse.append(delta);
                                    emitter.send(SseEmitter.event().data(delta));
                                } else if (event instanceof ThinkingBlockDeltaEvent e) {
                                    emitter.send(SseEmitter.event().name("thinking")
                                            .data(e.getDelta()));
                                } else if (event instanceof ToolCallStartEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_call")
                                            .data(objectToJson(Map.of(
                                                    "action", "start",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolCallEndEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_call")
                                            .data(objectToJson(Map.of(
                                                    "action", "end",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolResultStartEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectToJson(Map.of(
                                                    "action", "start",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                } else if (event instanceof ToolResultTextDeltaEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectToJson(Map.of(
                                                    "action", "delta",
                                                    "id", e.getToolCallId(),
                                                    "delta", e.getDelta()
                                            ))));
                                } else if (event instanceof ToolResultEndEvent e) {
                                    emitter.send(SseEmitter.event().name("tool_result")
                                            .data(objectToJson(Map.of(
                                                    "action", "end",
                                                    "id", e.getToolCallId(),
                                                    "name", e.getToolCallName()
                                            ))));
                                }
                            } catch (IOException ex) {
                                clientDisconnected[0] = true;
                                log.info("Client disconnected during DeepSeek stream, continuing to accumulate response");
                            }
                        }
                    })
                    .blockLast();

            // Save assistant message BEFORE sending done event,
            // so the frontend's loadMessages() call on done can see it
            String responseText = fullResponse.toString();
            if (!responseText.isEmpty()) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", responseText, "text");
                } catch (Exception e) {
                    log.warn("Failed to save assistant message for dialogue {}: {}", dialogueId, e.getMessage());
                }
            }

            // Self-check: validate response when tools were invoked
            if (invokedTools[0] && !clientDisconnected[0] && !responseText.isEmpty()) {
                try {
                    String corrected = performSelfCheck(userMessage, responseText);
                    if (corrected != null && !corrected.equals(responseText)) {
                        emitter.send(SseEmitter.event().name("corrected").data(corrected));
                        // Update the saved message
                        dialogueService.addMessage(dialogueId, "assistant", corrected, "text");
                        log.info("Self-check corrected response for dialogue {}", dialogueId);
                    }
                } catch (Exception e) {
                    log.warn("Self-check failed for dialogue {}: {}", dialogueId, e.getMessage());
                }
            }

            if (!clientDisconnected[0]) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }
        } catch (Exception e) {
            // On error, try to save partial response
            if (fullResponse.length() > 0) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text");
                } catch (Exception ignored) {}
            }
            handleStreamError(emitter, e);
        }
    }

    private void streamChatWithZhipu(Long dialogueId, String userMessage,
                                     List<ContentBlock> fileBlocks,
                                     SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();

            String fileContext = buildFileTextContext(dialogueId);
            String enriched = buildEnrichedMessage(fileContext, userMessage);

            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text", enriched));

            // Add images only (text file content is already in enriched)
            boolean hasImages = false;
            for (ContentBlock block : fileBlocks) {
                if (block instanceof ImageBlock ib) {
                    hasImages = true;
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

            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", contentParts));

            // Build the request body
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", zhipuModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", true);

            String jsonBody = objectToJson(requestBody);

            // Make HTTP request to ZhiPu API
            HttpURLConnection conn = (HttpURLConnection) URI.create(zhipuUrl + "/chat/completions").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + zhipuApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("智谱API请求失败: HTTP " + responseCode + " - " + errorBody));
                } catch (IOException ignored) {}
                emitter.complete();
                return;
            }

            // Stream the SSE response, continue accumulating even if client disconnects
            boolean clientDisconnected = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        // Parse delta content from the JSON
                        String delta = extractZhipuDelta(data);
                        if (delta != null && !delta.isEmpty()) {
                            fullResponse.append(delta);
                            if (!clientDisconnected) {
                                try {
                                    emitter.send(SseEmitter.event().data(delta));
                                } catch (IOException ex) {
                                    clientDisconnected = true;
                                    log.info("Client disconnected during ZhiPu stream, continuing to accumulate response");
                                }
                            }
                        }
                    }
                }
            }

            // Save assistant message BEFORE sending done event
            if (fullResponse.length() > 0) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text");
                } catch (Exception e) {
                    log.warn("Failed to save assistant message for dialogue {}: {}", dialogueId, e.getMessage());
                }
            }

            if (!clientDisconnected) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }

        } catch (Exception e) {
            // On error, try to save partial response
            if (fullResponse.length() > 0) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text");
                } catch (Exception ignored) {}
            }
            try {
                emitter.send(SseEmitter.event().name("error").data("智谱调用失败: " + e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    private String extractZhipuDelta(String jsonData) {
        // Parse ZhiPu/OpenAI SSE delta: {"choices":[{"delta":{"content":"..."}}]}
        try {
            int contentStart = jsonData.indexOf("\"content\":\"");
            if (contentStart < 0) return "";
            contentStart += 11; // length of "content":"
            int contentEnd = jsonData.indexOf("\"", contentStart);
            if (contentEnd < 0) return "";
            String content = jsonData.substring(contentStart, contentEnd);
            // Unescape JSON string
            return content.replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return "";
        }
    }

    private String objectToJson(Object obj) {
        // Simple JSON serialization for our use case
        if (obj instanceof Map map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : (Set<Map.Entry>) map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(objectToJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(objectToJson(item));
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        } else if (obj instanceof Boolean b) {
            return b.toString();
        } else if (obj instanceof Number n) {
            return n.toString();
        }
        return "null";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
        sb.append("请分析以下资料来回答问题。\n");
        sb.append("要求：答案必须直接引用资料中的原文，不得添加资料中没有的信息。\n");
        sb.append("\n文件内容：\n").append(fileContext);
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
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "deepseek-chat");
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "你是一个严谨的回答质量检查员，检查回答是否存在事实错误、幻觉和表达问题。"),
                    Map.of("role", "user", "content", checkPrompt)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 4096);

            String jsonBody = objectToJson(requestBody);

            HttpURLConnection conn = (HttpURLConnection) URI.create(deepseekApiUrl + "/chat/completions").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return responseText;
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Extract content from: {"choices":[{"message":{"content":"..."}}]}
            int contentStart = responseBody.indexOf("\"content\":\"");
            if (contentStart < 0) return responseText;
            contentStart += 11;
            int contentEnd = responseBody.indexOf("\"", contentStart);
            if (contentEnd < 0) return responseText;

            String result = responseBody.substring(contentStart, contentEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r");

            if (result.contains("无需修改") || result.contains("没有问题")) {
                return responseText;
            }

            log.info("Self-check found issues, providing corrected response");
            return result;
        } catch (Exception e) {
            log.warn("Self-check HTTP call failed: {}", e.getMessage());
            return responseText;
        }
    }

    private String buildFileTextContext(Long dialogueId) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
                if (IMAGE_FORMATS.contains(ext)) continue;

                if (f.getFileSize() != null && f.getFileSize() > MAX_FILE_SIZE) {
                    sb.append("【来源：").append(f.getTitle()).append("】（文件过大，跳过内容提取）\n");
                    continue;
                }

                String content = extractFileContent(Path.of(f.getFilePath()), ext);
                if (content != null && !content.isBlank()) {
                    String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
                    sb.append("【来源：").append(f.getTitle()).append("】\n").append(preview).append("\n\n");
                } else {
                    sb.append("【来源：").append(f.getTitle()).append("】（无法提取文字内容）\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<ContentBlock> buildFileBlocks(Long dialogueId) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return List.of();

            List<ContentBlock> blocks = new ArrayList<>();
            StringBuilder textIntro = new StringBuilder("以下是用户上传的文件：\n");

            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
                Path filePath = Path.of(f.getFilePath());
                if (!Files.exists(filePath)) continue;

                if (IMAGE_FORMATS.contains(ext)) {
                    if (!textIntro.isEmpty()) {
                        blocks.add(TextBlock.builder().text(textIntro.toString()).build());
                        textIntro.setLength(0);
                    }
                    String mediaType = getImageMimeType(ext);
                    String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
                    blocks.add(new ImageBlock(new Base64Source(mediaType, base64)));
                    textIntro.append("- ").append(f.getTitle()).append("（图片）\n");
                } else {
                    if (f.getFileSize() != null && f.getFileSize() > MAX_FILE_SIZE) {
                        textIntro.append("- ").append(f.getTitle()).append("（文件过大，跳过内容提取）\n");
                        continue;
                    }
                    String content = extractFileContent(filePath, ext);
                    if (content != null && !content.isBlank()) {
                        String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
                        textIntro.append("- ").append(f.getTitle()).append("：\n").append(preview).append("\n");
                    } else {
                        textIntro.append("- ").append(f.getTitle()).append("（无法提取文字内容）\n");
                    }
                }
            }

            if (!textIntro.isEmpty()) {
                blocks.add(TextBlock.builder().text(textIntro.toString()).build());
            }

            return blocks;
        } catch (Exception e) {
            return List.of();
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
