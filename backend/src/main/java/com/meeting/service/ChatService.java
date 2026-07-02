package com.meeting.service;

import com.meeting.agent.ListMeetingsTool;
import com.meeting.agent.ReadProfileTool;
import com.meeting.agent.SearchDocumentsTool;
import com.meeting.agent.SearchKnowledgeBaseTool;
import com.meeting.agent.SearchMeetingTitlesTool;
import com.meeting.agent.UpdateProfileTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.DocumentTextExtractor;
import com.meeting.entity.DialogueMessageEntity;
import com.meeting.repository.DialogueMessageRepository;
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
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();
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

            以下情况**严禁**调用 upload_to_knowledge_base（即使你觉得需要保存）：
            - 用户要求"总结"、"详细总结"、"简单摘要"、"提取要点"
            - 用户要求"改写"、"润色"
            - 用户要求"分析"、"查看"、"查阅"、"阅读"文件内容
            - 用户只问"这是什么"、"是什么内容"、"里面说了什么"
            - 用户只说"帮我处理这个文件"、"读一下这个文件"
            - 用户只是上传文件没有附带任何文字指令
            - 用户只是上传文件并说"你好"之类的问候语
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
    private final DialogueMessageRepository dialogueMessageRepository;
    private final UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool;
    private final SearchKnowledgeBaseTool searchKnowledgeBaseTool;
    private final SearchDocumentsTool searchDocumentsTool;
    private final ListMeetingsTool listMeetingsTool;
    private final SearchMeetingTitlesTool searchMeetingTitlesTool;
    private final ReadProfileTool readProfileTool;
    private final UpdateProfileTool updateProfileTool;
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
                       DialogueMessageRepository dialogueMessageRepository,
                       VectorizationService vectorizationService,
                       UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                       SearchKnowledgeBaseTool searchKnowledgeBaseTool,
                       SearchDocumentsTool searchDocumentsTool,
                       ListMeetingsTool listMeetingsTool,
                       SearchMeetingTitlesTool searchMeetingTitlesTool,
                       ReadProfileTool readProfileTool,
                       UpdateProfileTool updateProfileTool) {
        this.pgAgentStateStore = pgAgentStateStore;
        this.dialogueMessageRepository = dialogueMessageRepository;
        this.vectorizationService = vectorizationService;
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
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .disableSessionPersistence()
                .enableTaskList(false)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .build();
    }

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        executor.submit(() -> {
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
                            } catch (IOException | IllegalStateException ex) {
                                clientDisconnected[0] = true;
                                log.info("Client disconnected during DeepSeek stream: {}", ex.getMessage());
                            }
                        }
                    })
                    .blockLast();

            // Self-check: validate response when tools were invoked
            String responseText = fullResponse.toString();
            String finalResponse = responseText;
            if (invokedTools[0] && !clientDisconnected[0] && !responseText.isEmpty()) {
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

            // Clean up: replace enriched user message with clean original in persisted state.
            try {
                AgentState st = pgAgentStateStore.get("default", "dialogue-" + dialogueId,
                        "agent_state", AgentState.class).orElse(null);
                if (st != null) {
                    var context = st.contextMutable();
                    for (int i = context.size() - 1; i >= 0; i--) {
                        if (context.get(i).getRole() == MsgRole.USER) {
                            // Build a clean UserMessage preserving original text and file metadata
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
                            context.set(i, builder.build());
                            break;
                        }
                    }
                    pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", st);
                } else {
                    // New session: build initial state with user message + file metadata
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
                    AgentState ns = AgentState.builder().sessionId("dialogue-" + dialogueId).build();
                    ns.contextMutable().add(builder.build());
                    String respText = fullResponse.toString();
                    if (!respText.isEmpty()) {
                        ns.contextMutable().add(new AssistantMessage(respText));
                    }
                    pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", ns);
                    log.info("Persisted user message to agent state for dialogue {} (state was empty, with file meta)", dialogueId);
                }
            } catch (Exception e) {
                log.warn("Failed to clean up user message for dialogue {}: {}", dialogueId, e.getMessage());
            }

            // Persist user + assistant messages to dialogue_messages
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

                if (!finalResponse.isEmpty()) {
                    DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                    asstMsg.setDialogueId(dialogueId);
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(finalResponse);
                    asstMsg.setMessageType("text");
                    dialogueMessageRepository.save(asstMsg);
                }
            } catch (Exception e) {
                log.warn("Failed to persist dialogue_messages for dialogue {}: {}", dialogueId, e.getMessage());
            }

            try {
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (IOException | IllegalStateException ignored) {
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
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
                                        } catch (IOException | IllegalStateException ex) {
                                            clientDisconnected[0] = true;
                                            log.info("Client disconnected during ZhiPu stream: {}", ex.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .blockLast();

            // Clean up: replace enriched user message with clean original in persisted state.
            try {
                AgentState st = pgAgentStateStore.get("default", "dialogue-" + dialogueId,
                        "agent_state", AgentState.class).orElse(null);
                if (st != null) {
                    var ctx = st.contextMutable();
                    for (int i = ctx.size() - 1; i >= 0; i--) {
                        if (ctx.get(i).getRole() == MsgRole.USER) {
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
                            ctx.set(i, builder.build());
                            break;
                        }
                    }
                    pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", st);
                } else {
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
                    AgentState ns = AgentState.builder().sessionId("dialogue-" + dialogueId).build();
                    ns.contextMutable().add(builder.build());
                    String respText = fullResponse.toString();
                    if (!respText.isEmpty()) {
                        ns.contextMutable().add(new AssistantMessage(respText));
                    }
                    pgAgentStateStore.save("default", "dialogue-" + dialogueId, "agent_state", ns);
                    log.info("Persisted user message to agent state for dialogue {} (state was empty, with file meta)", dialogueId);
                }
            } catch (Exception e) {
                log.warn("Failed to clean up user message for dialogue {}: {}", dialogueId, e.getMessage());
            }

            // Persist user + assistant messages to dialogue_messages
            try {
                String respText = fullResponse.toString();
                String filesJson = messageFiles != null && !messageFiles.isEmpty()
                        ? objectMapper.writeValueAsString(messageFiles) : null;
                DialogueMessageEntity dmUser = new DialogueMessageEntity();
                dmUser.setDialogueId(dialogueId);
                dmUser.setRole("user");
                dmUser.setContent(userMessage);
                dmUser.setMessageType("text");
                dmUser.setFiles(filesJson);
                dialogueMessageRepository.save(dmUser);

                if (!respText.isEmpty()) {
                    DialogueMessageEntity asstMsg = new DialogueMessageEntity();
                    asstMsg.setDialogueId(dialogueId);
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(respText);
                    asstMsg.setMessageType("text");
                    dialogueMessageRepository.save(asstMsg);
                }
            } catch (Exception e) {
                log.warn("Failed to persist dialogue_messages for dialogue {}: {}", dialogueId, e.getMessage());
            }

            try {
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (IOException | IllegalStateException ignored) {
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
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
     * Parse file metadata array from metadata JSON string.
     * New format: {"files":[{"fileId":"uuid","filePath":"/path","ext":".docx",...}]}
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFilesFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return List.of();
        try {
            int filesIdx = metadata.indexOf("\"files\"");
            if (filesIdx < 0) return List.of();
            // Find the start of the JSON array after "files":
            int arrayStart = metadata.indexOf("[", filesIdx);
            if (arrayStart < 0) return List.of();
            // Find the matching closing bracket (simple approach: find last ] after arrayStart)
            int arrayEnd = metadata.lastIndexOf("]");
            if (arrayEnd <= arrayStart) return List.of();
            String arrayJson = metadata.substring(arrayStart, arrayEnd + 1);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var list = mapper.readValue(arrayJson, List.class);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
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
                    String mediaType = getImageMimeType(ext.toLowerCase());
                    String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
                    blocks.add(new ImageBlock(new Base64Source(mediaType, base64)));
                } catch (IOException ignored) {}
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
