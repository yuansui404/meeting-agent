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
            你是会议纪要智能助手。你的人格和行为规范已定义在 AGENTS.md 中，请严格遵守。

            ## 子 agent
            你可以使用 agentSpawn(agent_id, task, timeout) 创建子 agent 来委派独立任务：

            - rewrite_agent — 改写成正式会议纪要
              重要：收到改写请求时，直接调用 agentSpawn("rewrite_agent", "将以下内容改写为正式会议纪要：\n[原始内容]", 120)
              不要自己先搜索风格，rewrite_agent 会自行搜索知识库学习风格
            - response-checker — 回答质量检查
              使用条件：当你调用了 search_knowledge_base / search_documents / search_meeting_titles 等检索工具后，必须调用此子 agent 校验回答
              调用方式：agentSpawn("response-checker", "检查以下回答质量。\n检索资料：{你刚检索到的内容}\n用户问题：{原始问题}\nAI回答：{你的回答}", 60)
            - transcription-checker — 转写文本校对
              使用条件：当你调用 call_mimo_asr 工具获得转写文本后，应该 spawn 此子 agent 校对
              调用方式：agentSpawn("transcription-checker", "校对以下转写文本：\n[转写文本]", 60)
            - general-purpose — 通用子 agent，用于任何可完全委派的独立任务
              适用场景：需要大量计算、需要独立上下文、可以并行处理的任务
              使用方法：agentSpawn("general-purpose", "具体的任务描述...", 120)
            """;

    @Bean
    public OpenAIChatModel openAIChatModel(@Value("${deepseek.api-key:}") String apiKey,
                                           @Value("${deepseek.model:deepseek-chat}") String modelName,
                                           @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(apiUrl)
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
