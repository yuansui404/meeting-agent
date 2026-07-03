package com.meeting.config;

import com.meeting.agent.*;
import com.meeting.service.UploadToKnowledgeBaseTool;
import com.meeting.state.PgAgentStateStore;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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
                           @Value("${tavily.api-key:}") String tavilyApiKey) {
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(uploadToKnowledgeBaseTool);
        tk.registerAgentTool(searchKnowledgeBaseTool);
        tk.registerAgentTool(searchDocumentsTool);
        tk.registerAgentTool(listMeetingsTool);
        tk.registerAgentTool(searchMeetingTitlesTool);
        tk.registerAgentTool(readProfileTool);
        tk.registerAgentTool(updateProfileTool);

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
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .workspace(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "meeting-agent-workspace"))
                .disableSessionPersistence()
                .disableWorkspaceContext()
                .disableMemoryTools()
                .disableMemoryHooks()
                .enableTaskList(false)
                .maxIters(8)
                .stateStore(pgAgentStateStore)
                .disableFilesystemTools()
                .build();
    }
}
