package com.meeting.config;

import com.meeting.agent.*;
import com.meeting.service.UploadToKnowledgeBaseTool;
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
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;


@Slf4j
@Configuration
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
    public Toolkit toolkit(UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                           SearchKnowledgeBaseTool searchKnowledgeBaseTool,
                           SearchDocumentsTool searchDocumentsTool,
                           ListMeetingsTool listMeetingsTool,
                           SearchMeetingTitlesTool searchMeetingTitlesTool,
                           ReadProfileTool readProfileTool,
                           UpdateProfileTool updateProfileTool,
                           CallMiMoAsrTool callMiMoAsrTool,
                           UnderstandImageTool understandImageTool,
                           @Value("${tavily.api-key:}") String tavilyApiKey) {
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(uploadToKnowledgeBaseTool);
        tk.registerAgentTool(searchKnowledgeBaseTool);
        tk.registerAgentTool(searchDocumentsTool);
        tk.registerAgentTool(listMeetingsTool);
        tk.registerAgentTool(searchMeetingTitlesTool);
        tk.registerAgentTool(readProfileTool);
        tk.registerAgentTool(updateProfileTool);
        tk.registerAgentTool(callMiMoAsrTool);
        tk.registerAgentTool(understandImageTool);

        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            try {
                McpClientWrapper tavilyClient = McpClientBuilder.create("tavily")
                        .stdioTransport("npx", List.of("tavily-mcp"), Map.of("TAVILY_API_KEY", tavilyApiKey))
                        .timeout(Duration.ofSeconds(30))
                        .buildSync();
                tk.registerMcpClient(tavilyClient).block();
                log.info("Tavily MCP client initialized successfully");
            } catch (Exception e) {
                log.warn("Tavily MCP client init failed (web search unavailable): {}", e.getMessage());
            }
        } else {
            log.info("TAVILY_API_KEY not configured, web search disabled");
        }

        return tk;
    }

    @Bean
    public HarnessAgent meetingAssistantAgent(OpenAIChatModel openAIChatModel,
                                              Toolkit toolkit,
                                              PgAgentStateStore pgAgentStateStore) {
        return HarnessAgent.builder()
                .name("MeetingAssistant")
                .description("会议纪要智能助手，支持子 agent 委派")
                .sysPrompt(SYSTEM_PROMPT)
                .model(openAIChatModel)
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .workspace(java.nio.file.Path.of(System.getProperty("user.dir"), ".agentscope", "workspace"))
                .disableSessionPersistence()
                .disableMemoryTools()
                .disableMemoryHooks()
                .subagent(SubagentDeclaration.builder()
                        .name("rewrite_agent")
                        .description("改写成正式会议纪要，润色校对")
                        .mode(SubagentDeclaration.Mode.SUBAGENT)
                        .steps(15)
                        .tools(List.of("search_documents", "search_knowledge_base"))
                        .inlineAgentsBody("""
                                你是专业的会议纪要撰写助手。将用户提供的录音/会议记录改写为正式会议纪要。

                                ## 工作流程
                                1. 用 search_documents 搜索"会议纪要"，学习知识库中历史纪要的格式和风格
                                2. 严格按照历史纪要的格式进行改写
                                3. 保持原文关键信息（参会人、时间、决定、结论）不变

                                ## 要求
                                - 输出严格遵循知识库中历史纪要的格式
                                - 语言正式、简洁、结构清晰
                                - 不添加原文没有的信息
                                """)
                        .build())
                .enableTaskList(false)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .build();
    }
}
