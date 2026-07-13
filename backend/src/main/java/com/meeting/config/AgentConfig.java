package com.meeting.config;

import com.meeting.agent.*;
import com.meeting.conversation.middleware.FileContextMiddleware;
import com.meeting.conversation.middleware.ProfileIndexMiddleware;
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
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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
                           @Value("${tavily.api-key:}") String tavilyApiKey) {
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(uploadToKnowledgeBaseTool);
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
                        .stdioTransport("npx", List.of("tavily-mcp@0.2.20"), Map.of("TAVILY_API_KEY", tavilyApiKey))
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
                                              PgAgentStateStore pgAgentStateStore,
                                              FileContextMiddleware fileContextMiddleware,
                                              ProfileIndexMiddleware profileIndexMiddleware) {
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
                .enableTaskList(false)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .middleware(profileIndexMiddleware)
                .middleware(fileContextMiddleware)
                .subagent(SubagentDeclaration.builder()
                        .name("search_agent")
                        .description("检索子 agent，负责知识库内容搜索和会议标题搜索。当用户查询会议内容、知识库时派发给此 agent")
                        .inlineAgentsBody("你是一个检索专家。你的职责是根据用户查询，从知识库中找到最相关的信息。\n\n" +
                                "你的工具：\n" +
                                "- search_documents：完整 RAG 检索管线，返回带证据等级(evidenceLevel)的结果\n" +
                                "- search_meeting_titles：按标题关键词搜索会议\n\n" +
                                "工作流程：\n" +
                                "1. 先调用 search_documents(query) 进行检索\n" +
                                "2. 查看结果中的 evidenceLevel：\n" +
                                "   - SUFFICIENT → 直接返回结果\n" +
                                "   - WEAK/NONE → 尝试以下策略：\n" +
                                "     a. 改写 query 换种说法再次 search_documents\n" +
                                "     b. 调用 search_meeting_titles 定位会议，再用会议名搜索\n" +
                                "     c. 结合多次搜索的结果\n" +
                                "3. 重试上限：最多尝试 3 次（含首次），仍无法获得 SUFFICIENT 证据，\n" +
                                "   则返回明确提示\"未找到相关内容\"\n" +
                                "4. 返回最终结果（包括证据等级、引文和检索内容）")
                        .tools(List.of("search_documents", "search_meeting_titles"))
                        .steps(8)
                        .build())
                .build();
    }
}
