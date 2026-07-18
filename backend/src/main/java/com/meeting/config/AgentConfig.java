package com.meeting.config;

import com.meeting.agent.*;
import com.meeting.conversation.middleware.FileContextMiddleware;
import com.meeting.conversation.middleware.ProfileIndexMiddleware;
import com.meeting.conversation.middleware.SearchCheckReminderMiddleware;
import com.meeting.knowledgebase.tool.UploadToKnowledgeBaseTool;
import com.meeting.state.PgAgentStateStore;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;


@Slf4j
@Configuration
@EnableConfigurationProperties({FileProperties.class, MimoProperties.class, EmbeddingProperties.class, RagProperties.class})
public class AgentConfig {

    public static final String SYSTEM_PROMPT = """
            你是会议纪要智能助手。你的人格、工具使用规范、子 agent 体系、行为准则已全部定义在 AGENTS.md 中，请严格遵守。
            """;

    @Bean
    public OpenAIChatModel openAIChatModel(DeepSeekProperties props) {
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .baseUrl(props.getUrl())
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();
    }

    @Bean
    public OpenAIChatModel nonStreamingOpenAIChatModel(DeepSeekProperties props) {
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .baseUrl(props.getUrl())
                .formatter(new DeepSeekFormatter())
                .stream(false)
                .build();
    }

    @Bean
    public Toolkit toolkit(UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                           SearchDocumentsTool searchDocumentsTool,
                           ListMeetingsTool listMeetingsTool,
                           SearchMeetingTitlesTool searchMeetingTitlesTool,
                           ReadProfileTool readProfileTool,
                           UpdateProfileTool updateProfileTool,
                           CallMiMoAsrTool callMiMoAsrTool,
                           UnderstandImageTool understandImageTool,
                           ListTemplatesTool listTemplatesTool,
                           GetStyleExamplesTool getStyleExamplesTool,
                           ResponseCheckTool responseCheckTool,
                           ExportDocxTool exportDocxTool,
                           @Value("${tavily.api-key:}") String tavilyApiKey) {
        Toolkit tk = new Toolkit();

        // 创建工具组: utility 始终激活，其余按场景由 Agent 自主激活
        tk.createToolGroup("utility", "通用工具(画像/知识库/检查)", true);
        tk.createToolGroup("rewrite", "文本改写润色工具", false);
        tk.createToolGroup("search", "知识库搜索检索工具", false);
        tk.createToolGroup("file", "文件音频图片理解工具", false);

        // ── utility 组（始终激活）──
        tk.registration().agentTool(uploadToKnowledgeBaseTool).group("utility").apply();
        tk.registration().agentTool(readProfileTool).group("utility").apply();
        tk.registration().agentTool(updateProfileTool).group("utility").apply();
        tk.registration().agentTool(responseCheckTool).group("utility").apply();

        // ── rewrite 组 ──
        tk.registration().agentTool(getStyleExamplesTool).group("rewrite").apply();
        tk.registration().agentTool(listTemplatesTool).group("rewrite").apply();
        tk.registration().agentTool(exportDocxTool).group("rewrite").apply();

        // ── search 组 ──
        tk.registration().agentTool(searchDocumentsTool).group("search").apply();
        tk.registration().agentTool(searchMeetingTitlesTool).group("search").apply();
        tk.registration().agentTool(listMeetingsTool).group("search").apply();

        // ── file 组 ──
        tk.registration().agentTool(callMiMoAsrTool).group("file").apply();
        tk.registration().agentTool(understandImageTool).group("file").apply();

        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            // 异步初始化 Tavily MCP 客户端，避免阻塞应用启动
            McpClientBuilder.create("tavily")
                    .stdioTransport("npx", List.of("tavily-mcp@0.2.20"), Map.of("TAVILY_API_KEY", tavilyApiKey))
                    .timeout(Duration.ofSeconds(30))
                    .buildAsync()
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .doOnNext(tavilyClient -> {
                        tk.registerMcpClient(tavilyClient).block();
                        // MCP 工具从 ungrouped 移到 search 组
                        var ungroupedGroup = tk.getToolGroup("ungrouped");
                        var searchGroup = tk.getToolGroup("search");
                        if (ungroupedGroup != null && searchGroup != null) {
                            for (var toolName : List.of("tavily_search", "tavily_extract", "tavily_crawl", "tavily_map", "tavily_research")) {
                                ungroupedGroup.removeTool(toolName);
                                searchGroup.addTool(toolName);
                            }
                            log.info("MCP tools moved to search group: {}", searchGroup.getTools());
                        }
                        log.info("Tavily MCP client initialized successfully");
                    })
                    .doOnError(e -> log.warn("Tavily MCP client init failed (web search unavailable): {}", e.getMessage()))
                    .subscribe();
        } else {
            log.info("TAVILY_API_KEY not configured, web search disabled");
        }

        return tk;
    }

    @Bean
    public HarnessAgent meetingAssistantAgent(OpenAIChatModel openAIChatModel,
                                              Toolkit toolkit,
                                              PgAgentStateStore pgAgentStateStore,
                                              FileContextMiddleware fileContextMiddleware,
                                              ProfileIndexMiddleware profileIndexMiddleware,
                                              SearchCheckReminderMiddleware searchCheckReminderMiddleware) {
        return HarnessAgent.builder()
                .name("MeetingAssistant")
                .description("会议纪要智能助手，支持子 agent 委派")
                .sysPrompt(SYSTEM_PROMPT)
                .model(openAIChatModel)
                .toolkit(toolkit)
                .enableMetaTool(true)   // 允许 Agent 自主切换 ToolGroup
                // BYPASS: 后端 API 场景无交互用户，工具权限由 ToolGroup 编译期控制
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .workspace(java.nio.file.Path.of(System.getProperty("user.dir"), ".agentscope", "workspace"))
                .projectGlobalSkillsDir(java.nio.file.Path.of(System.getProperty("user.dir"), ".agentscope", "workspace", "skills"))
                .disableSessionPersistence()
                .disableMemoryTools()
                .disableMemoryHooks()
                .enableTaskList(false)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .middleware(profileIndexMiddleware)
                .middleware(fileContextMiddleware)
                .middleware(searchCheckReminderMiddleware)
                .build();
    }

    @Bean
    public TaskRepository taskRepository(HarnessAgent meetingAssistantAgent) {
        try {
            var subagentMwField = HarnessAgent.class.getDeclaredField("subagentMiddleware");
            subagentMwField.setAccessible(true);
            Object subagentMiddleware = subagentMwField.get(meetingAssistantAgent);
            var taskRepoField = subagentMiddleware.getClass().getDeclaredField("taskRepository");
            taskRepoField.setAccessible(true);
            return (TaskRepository) taskRepoField.get(subagentMiddleware);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to expose TaskRepository bean", e);
        }
    }
}
