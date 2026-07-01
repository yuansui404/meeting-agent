package com.meeting.service;

import com.meeting.agent.ListMeetingsTool;
import com.meeting.agent.ReadProfileTool;
import com.meeting.agent.SearchDocumentsTool;
import com.meeting.agent.SearchKnowledgeBaseTool;
import com.meeting.agent.SearchMeetingTitlesTool;
import com.meeting.agent.UpdateProfileTool;
import com.meeting.common.DocumentTextExtractor;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.state.PgAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.event.*;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.OpenAIClient;
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
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RefreshScope
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SYSTEM_PROMPT = """
            你是会议纪要智能助手，具备以下能力：

            ## 工具
            - search_knowledge_base — 搜索知识库中的会议记录内容（转录文本），获取具体讨论、决定、与会人等信息
            - search_documents — 搜索知识库中的文档/文件内容（语义+全文融合搜索），返回证据等级(evidenceLevel)和引文信息。当需要查阅文档内容时使用。支持可选参数 timeRange 限定时间范围
            - list_meetings — 浏览会议记录列表，查看有哪些会议
            - search_meeting_titles — 通过标题关键词搜索特定会议
            - tavily_search — 联网搜索实时信息（如最新政策、技术文档、外部资料等）
            - upload_to_knowledge_base — [仅用户明确要求时使用] 将对话中的文件保存到知识库
            - read_profile — 读取用户画像（偏好、习惯、个人信息）
            - update_profile — 更新用户画像，让 agent 记住用户信息

            ## 关于 upload_to_knowledge_base 的严格规则
            只有在用户明确说出以下词语时才调用 upload_to_knowledge_base：
            - "保存到知识库"
            - "上传到知识库"
            - "加入知识库"

            以下情况严禁调用 upload_to_knowledge_base（即使你觉得需要保存）：
            - 用户要求"总结"、"详细总结"、"简单摘要"、"提取要点"
            - 用户要求"改写"、"润色"
            - 用户要求"分析"、"查看"、"查阅"文件内容
            - 用户只问"这是什么"、"是什么内容"
            如果不确定，就不要调用。

            ## 子 agent
            你可以使用 agentSpawn(agent_id, task, timeout) 创建子 agent 来委派独立任务：

            - rewrite_agent — 改写成正式会议纪要（仅改写任务使用）
            - general-purpose — 通用子 agent，用于任何可完全委派的独立任务
              适用场景：需要大量计算、需要独立上下文、可以并行处理的任务
              使用方法：agentSpawn("general-purpose", "具体的任务描述...", 120)

            ## 要求
            - 回答简洁准确
            - 引用知识库内容时注明来源会议名称
            - 联网搜索结果需说明信息来源
            - search_documents 返回的 evidenceLevel 标识检索结果质量：
              SUFFICIENT=充分, PARTIAL=部分, WEAK=弱, NONE=无结果
              如果 evidenceLevel 为 WEAK 或 NONE，应尝试改写搜索关键词后再次检索
            - 对于复杂问题，可以组合使用 search_documents 和 search_knowledge_base
              分别搜索文档内容和会议转录，获得更全面的信息
            - 对于需要多步推理的复杂查询，可以逐步执行：
              先搜索会议信息 → 分析结果 → 再搜索具体内容 → 综合回答
            - 不确定时可以使用 search_meeting_titles 先确定有哪些相关会议，
              再用 search_documents/search_knowledge_base 获取具体内容
            - 多步检索时，每步使用 refine 后的查询词，避免简单重复
            """;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit for file reading

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    private final HarnessAgent agent;
    private final PgAgentStateStore pgAgentStateStore;
    private final UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool;
    private final SearchKnowledgeBaseTool searchKnowledgeBaseTool;
    private final SearchDocumentsTool searchDocumentsTool;
    private final ListMeetingsTool listMeetingsTool;
    private final SearchMeetingTitlesTool searchMeetingTitlesTool;
    private final ReadProfileTool readProfileTool;
    private final UpdateProfileTool updateProfileTool;
    private final ProfileService profileService;
    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final OpenAIClient openAIClient = new OpenAIClient();

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
                       PgAgentStateStore pgAgentStateStore,
                       MeetingMinutesRepository meetingRepository,
                       VectorizationService vectorizationService,
                       ProfileService profileService,
                       UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                       SearchKnowledgeBaseTool searchKnowledgeBaseTool,
                       SearchDocumentsTool searchDocumentsTool,
                       ListMeetingsTool listMeetingsTool,
                       SearchMeetingTitlesTool searchMeetingTitlesTool,
                       ReadProfileTool readProfileTool,
                       UpdateProfileTool updateProfileTool) {
        this.pgAgentStateStore = pgAgentStateStore;
        this.meetingRepository = meetingRepository;
        this.vectorizationService = vectorizationService;
        this.profileService = profileService;
        this.uploadToKnowledgeBaseTool = uploadToKnowledgeBaseTool;
        this.searchKnowledgeBaseTool = searchKnowledgeBaseTool;
        this.searchDocumentsTool = searchDocumentsTool;
        this.listMeetingsTool = listMeetingsTool;
        this.searchMeetingTitlesTool = searchMeetingTitlesTool;
        this.readProfileTool = readProfileTool;
        this.updateProfileTool = updateProfileTool;
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
        toolkit.registerAgentTool(searchDocumentsTool);
        toolkit.registerAgentTool(listMeetingsTool);
        toolkit.registerAgentTool(searchMeetingTitlesTool);
        toolkit.registerAgentTool(readProfileTool);
        toolkit.registerAgentTool(updateProfileTool);

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
                .description("会议纪要智能助手，支持子 agent 委派")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .build();
    }

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                // Parse fileIds from metadata to know which files are "new" with this message
                List<Long> messageFileIds = parseFileIdsFromMetadata(metadata);

                // 2. Build file blocks (all dialogue files for context, marked new vs old)
                List<ContentBlock> fileBlocks = buildFileBlocks(dialogueId, messageFileIds);
                boolean hasImages = fileBlocks.stream().anyMatch(b -> b instanceof ImageBlock);

                // 3. ZhiPu only for multimodal (images), DeepSeek for all text
                if (hasImages) {
                    streamChatWithZhipu(dialogueId, userMessage, fileBlocks, messageFileIds, emitter);
                } else {
                    streamChatWithDeepSeek(dialogueId, userMessage, fileBlocks, messageFileIds, emitter);
                }
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                        List<ContentBlock> fileBlocks,
                                        List<Long> messageFileIds,
                                        SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            String profileContext = profileService.buildProfileContext();
            String messageWithProfile = profileContext.isEmpty() ? userMessage
                    : profileContext + "\n\n" + userMessage;

            UserMessage msg;
            String fileContext = buildFileTextContext(dialogueId, messageFileIds);
            if (!fileBlocks.isEmpty()) {
                String enriched = buildEnrichedMessage(fileContext, messageWithProfile);
                List<ContentBlock> blocks = new ArrayList<>();
                blocks.add(TextBlock.builder().text(enriched).build());
                blocks.addAll(fileBlocks);
                msg = new UserMessage(blocks);
            } else {
                String enriched = buildEnrichedMessage(fileContext, messageWithProfile);
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
                                    String delta = e.getDelta();
                                    if (delta != null) {
                                        delta = delta.replaceAll("(?i)exit\\s*code:?\\s*\\d+", "").trim();
                                        if (!delta.isEmpty()) {
                                            emitter.send(SseEmitter.event().name("thinking").data(delta));
                                        }
                                    }
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

            // Self-check: validate response when tools were invoked
            String responseText = fullResponse.toString();
            if (invokedTools[0] && !clientDisconnected[0] && !responseText.isEmpty()) {
                try {
                    String corrected = performSelfCheck(userMessage, responseText);
                    if (corrected != null && !corrected.equals(responseText)) {
                        emitter.send(SseEmitter.event().name("corrected").data(corrected));
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

            if (!clientDisconnected[0]) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }
        } catch (Exception e) {
            handleStreamError(emitter, e);
        }
    }

    private void streamChatWithZhipu(Long dialogueId, String userMessage,
                                     List<ContentBlock> fileBlocks,
                                     List<Long> messageFileIds,
                                     SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            String profileContext = profileService.buildProfileContext();
            String messageWithProfile = profileContext.isEmpty() ? userMessage
                    : profileContext + "\n\n" + userMessage;

            String fileContext = buildFileTextContext(dialogueId, messageFileIds);
            String enriched = buildEnrichedMessage(fileContext, messageWithProfile);

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
            sysMsg.setContent(SYSTEM_PROMPT);

            OpenAIMessage userMsg = new OpenAIMessage();
            userMsg.setRole("user");
            userMsg.setContent(contentParts);

            OpenAIRequest request = OpenAIRequest.builder()
                    .model(zhipuModel)
                    .messages(List.of(sysMsg, userMsg))
                    .stream(true)
                    .build();

            // Stream via OpenAIClient — ZhiPu uses /chat/completions (no /v1 prefix),
            // so override the default endpoint to empty and pass the full URL as baseUrl
            GenerateOptions opts = GenerateOptions.builder().endpointPath("").build();
            boolean[] clientDisconnected = {false};
            openAIClient.stream(zhipuApiKey, zhipuUrl + "/chat/completions", request, opts)
                    .doOnNext(response -> {
                        if (response.isChunk()) {
                            OpenAIMessage delta = response.getFirstChoice().getDelta();
                            if (delta != null) {
                                String content = delta.getContentAsString();
                                if (content != null && !content.isEmpty()) {
                                    fullResponse.append(content);
                                    if (!clientDisconnected[0]) {
                                        try {
                                            emitter.send(SseEmitter.event().data(content));
                                        } catch (IOException ex) {
                                            clientDisconnected[0] = true;
                                            log.info("Client disconnected during ZhiPu stream, continuing to accumulate response");
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .blockLast();

            if (!clientDisconnected[0]) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }

        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data("智谱调用失败: " + e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
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
                    .model("deepseek-chat")
                    .messages(List.of(
                            OpenAIMessage.builder().role("system")
                                    .content("你是一个严谨的回答质量检查员，检查回答是否存在事实错误、幻觉和表达问题。").build(),
                            OpenAIMessage.builder().role("user").content(checkPrompt).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(4096)
                    .build();

            OpenAIResponse response = openAIClient.call(deepseekApiKey, deepseekApiUrl, request);
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
            int fileIdsIdx = metadata.indexOf("\"fileIds\"");
            if (fileIdsIdx < 0) return List.of();
            int start = metadata.indexOf("[", fileIdsIdx);
            int end = metadata.indexOf("]", start);
            if (start < 0 || end < 0) return List.of();
            String arr = metadata.substring(start + 1, end);
            if (arr.isBlank()) return List.of();
            return Arrays.stream(arr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse fileIds from metadata: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Filter meetings to only include those matching the message's fileIds.
     * If messageFileIds is empty (e.g., existing messages without fileIds),
     * include all files for backward compatibility.
     */
    private List<MeetingMinutes> filterFilesByMessageIds(List<MeetingMinutes> allFiles, List<Long> messageFileIds) {
        if (messageFileIds == null || messageFileIds.isEmpty()) {
            return allFiles; // backward compatible: include all dialogue files
        }
        return allFiles.stream()
                .filter(f -> messageFileIds.contains(f.getId()))
                .toList();
    }

    private String buildFileTextContext(Long dialogueId, List<Long> messageFileIds) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return "";

            Set<Long> newFileIds = (messageFileIds == null || messageFileIds.isEmpty())
                    ? Set.of() : new HashSet<>(messageFileIds);
            boolean hasNewFiles = !newFileIds.isEmpty();

            StringBuilder sb = new StringBuilder();
            List<MeetingMinutes> newFiles = new ArrayList<>();
            List<MeetingMinutes> oldFiles = new ArrayList<>();
            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                if (newFileIds.contains(f.getId())) {
                    newFiles.add(f);
                } else {
                    oldFiles.add(f);
                }
            }

            if (hasNewFiles) {
                sb.append("【本次提交的文件 — 请重点参考这些文件回答】\n");
                for (MeetingMinutes f : newFiles) {
                    appendFileContent(sb, f);
                }
                sb.append("\n");
            }

            if (!oldFiles.isEmpty()) {
                sb.append("【对话历史中的文件 — 仅在用户提及相关内容时参考】\n");
                for (MeetingMinutes f : oldFiles) {
                    appendFileContent(sb, f);
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void appendFileContent(StringBuilder sb, MeetingMinutes f) {
        String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
        if (IMAGE_FORMATS.contains(ext)) return;

        if (f.getFileSize() != null && f.getFileSize() > MAX_FILE_SIZE) {
            sb.append("【来源：").append(f.getTitle()).append("】（文件过大，跳过内容提取）\n");
            return;
        }

        String content = extractFileContent(Path.of(f.getFilePath()), ext);
        if (content != null && !content.isBlank()) {
            String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
            sb.append("【来源：").append(f.getTitle()).append("】\n").append(preview).append("\n\n");
        } else {
            sb.append("【来源：").append(f.getTitle()).append("】（无法提取文字内容）\n");
        }
    }

    private List<ContentBlock> buildFileBlocks(Long dialogueId, List<Long> messageFileIds) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return List.of();

            List<ContentBlock> blocks = new ArrayList<>();
            Set<Long> newFileIds = (messageFileIds == null || messageFileIds.isEmpty())
                    ? Set.of() : new HashSet<>(messageFileIds);

            // Only return ImageBlock for images; text content is handled by buildFileTextContext
            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
                if (!IMAGE_FORMATS.contains(ext)) continue;
                Path filePath = Path.of(f.getFilePath());
                if (!Files.exists(filePath)) continue;

                String mediaType = getImageMimeType(ext);
                String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
                blocks.add(new ImageBlock(new Base64Source(mediaType, base64)));
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
