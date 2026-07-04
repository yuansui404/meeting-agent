# Agent 工具扩展实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为会议纪要 Agent 扩展 4 个工具（search_knowledge_base / list_meetings / search_meeting_titles / tavily_search），并将 RAG 注入方式从"硬编码预注入"改为"Agent 自主工具调用"。

**Architecture:** 复用现有 `UploadToKnowledgeBaseTool` 的 `AgentTool` 接口模式实现 3 个新工具；tavily_search 通过 AgentScope MCP Stdio 拉起 `npx tavily-mcp` 子进程。所有工具注册到 HarnessAgent 的 Toolkit。删除 ChatService 中的 `buildRagContext()` 硬编码逻辑。

**Tech Stack:** Java 21 / Spring Boot / AgentScope 2.0.0-RC3 / Tavily MCP / pgvector

## Global Constraints

- 所有新工具放在 `backend/src/main/java/com/meeting/agent/` 包下
- 实现 `io.agentscope.core.tool.AgentTool` 接口（已有 UploadToKnowledgeBaseTool 作为参考）
- MCP 客户端使用 `McpClientBuilder.create().stdioTransport()` API（注意：不是 `McpClientBuilder.stdio()`）
- Tavily MCP 需要环境变量 `TAVILY_API_KEY`，若未配置或启动失败则跳过，不影响其他工具
- Repository 方法使用 Spring Data JPA + native query
- 不需要 Maven 新依赖（MCP 支持已随 agentscope-harness bundle 引入）

---

### Task 1: MeetingMinutesRepository 新增查询方法

**Files:**
- Modify: `backend/src/main/java/com/meeting/repository/MeetingMinutesRepository.java`

**Interfaces:**
- Consumes: 无（独立修改）
- Produces: `findAllByOrderByCreatedAtDesc(Pageable)` 和 `searchByTitleKeyword(keyword, limit)` 两个方法供 Task 2/3 使用

- [ ] **Step 1: 修改 MeetingMinutesRepository.java，新增两个查询方法**

```java
package com.meeting.repository;

import com.meeting.entity.MeetingMinutes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingMinutesRepository extends JpaRepository<MeetingMinutes, Long> {

    List<MeetingMinutes> findByStatus(String status);

    List<MeetingMinutes> findByDialogueId(Long dialogueId);

    List<MeetingMinutes> findByKnowledgeBaseTrue();

    // 分页查询（按创建时间倒序）
    Page<MeetingMinutes> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 标题模糊匹配
    @Query(value = "SELECT * FROM meeting_minutes WHERE title ILIKE '%' || :keyword || '%' ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<MeetingMinutes> searchByTitleKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query(value = "SELECT * FROM meeting_minutes WHERE to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '')) @@ plainto_tsquery('simple', ?1)", nativeQuery = true)
    List<MeetingMinutes> fullTextSearch(String query);

    @Query(value = "SELECT * FROM meeting_minutes WHERE coalesce(title, '') || ' ' || coalesce(transcription, '') ILIKE '%' || ?1 || '%' ORDER BY similarity(coalesce(title, '') || ' ' || coalesce(transcription, ''), ?1) DESC", nativeQuery = true)
    List<MeetingMinutes> trigramSearch(String query);
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd /Users/ys/IdeaProjects/meeting-agent/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/meeting/repository/MeetingMinutesRepository.java
git commit -m "feat(repo): add paged list and title fuzzy search to MeetingMinutesRepository"
```

---

### Task 2: SearchKnowledgeBaseTool

**Files:**
- Create: `backend/src/main/java/com/meeting/agent/SearchKnowledgeBaseTool.java`

**Interfaces:**
- Consumes: `VectorizationService.searchSimilarWithScores(String query, int limit)` 返回 `List<ScoredVector>`。`MeetingMinutesRepository.findById(Long)` 用于查标题和日期。
- Produces: AgentTool 名称 `"search_knowledge_base"`，供后续注册到 Toolkit。

- [ ] **Step 1: 新建 SearchKnowledgeBaseTool.java**

```java
package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.VectorizationService;
import com.meeting.service.VectorizationService.ScoredVector;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SearchKnowledgeBaseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeBaseTool.class);

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;

    public SearchKnowledgeBaseTool(VectorizationService vectorizationService,
                                   MeetingMinutesRepository meetingRepository) {
        this.vectorizationService = vectorizationService;
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "search_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "搜索知识库中的会议记录内容。当用户询问会议具体内容、决定、讨论要点等需要查阅历史会议信息时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词，尽量简洁准确"
        ));
        properties.put("topK", Map.of(
                "type", "number",
                "description", "返回结果数，默认5，最少1，最多20"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String query = input.getOrDefault("query", "").toString();
        if (query.isBlank()) {
            return Mono.just(ToolResultBlock.text("搜索关键词不能为空"));
        }

        int topK = 5;
        Object topKObj = input.get("topK");
        if (topKObj instanceof Number n) {
            topK = Math.max(1, Math.min(20, n.intValue()));
        }

        try {
            List<ScoredVector> results = vectorizationService.searchSimilarWithScores(query, topK);
            if (results.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到相关结果。"));
            }

            // Build title cache for source attribution
            Map<Long, String> titleCache = new HashMap<>();
            Map<Long, String> dateCache = new HashMap<>();
            for (ScoredVector v : results) {
                titleCache.computeIfAbsent(v.meetingId(),
                        id -> meetingRepository.findById(id)
                                .map(MeetingMinutes::getTitle)
                                .orElse("未知文件"));
                dateCache.computeIfAbsent(v.meetingId(),
                        id -> meetingRepository.findById(id)
                                .map(m -> m.getMeetingDate() != null
                                        ? m.getMeetingDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        : "")
                                .orElse(""));
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (ScoredVector v : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", titleCache.getOrDefault(v.meetingId(), ""));
                item.put("content", v.content());
                item.put("similarity", Math.round(v.similarity() * 100.0) / 100.0);
                item.put("date", dateCache.getOrDefault(v.meetingId(), ""));
                items.add(item);
            }

            return Mono.just(ToolResultBlock.text(toJson(items)));
        } catch (Exception e) {
            log.warn("Knowledge base search failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("搜索知识库异常，请稍后重试"));
        }
    }

    private String toJson(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            Map<String, Object> item = items.get(i);
            int j = 0;
            for (var entry : item.entrySet()) {
                if (j++ > 0) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object val = entry.getValue();
                if (val instanceof String s) {
                    sb.append("\"").append(escapeJson(s)).append("\"");
                } else {
                    sb.append(val);
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd /Users/ys/IdeaProjects/meeting-agent/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/meeting/agent/SearchKnowledgeBaseTool.java
git commit -m "feat(agent): add search_knowledge_base tool for agent-driven RAG retrieval"
```

---

### Task 3: ListMeetingsTool

**Files:**
- Create: `backend/src/main/java/com/meeting/agent/ListMeetingsTool.java`

**Interfaces:**
- Consumes: `MeetingMinutesRepository.findAllByOrderByCreatedAtDesc(Pageable)`
- Produces: AgentTool 名称 `"list_meetings"`

- [ ] **Step 1: 新建 ListMeetingsTool.java**

```java
package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class ListMeetingsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListMeetingsTool.class);

    private final MeetingMinutesRepository meetingRepository;

    public ListMeetingsTool(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "list_meetings";
    }

    @Override
    public String getDescription() {
        return "浏览会议记录列表。当用户想知道最近有哪些会议、查看会议概览时使用。按创建时间倒序排列。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("page", Map.of(
                "type", "number",
                "description", "页码，从0开始（默认0）"
        ));
        properties.put("size", Map.of(
                "type", "number",
                "description", "每页条数（默认10，最多50）"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();

        int page = 0;
        if (input.get("page") instanceof Number n) {
            page = Math.max(0, n.intValue());
        }

        int size = 10;
        if (input.get("size") instanceof Number n) {
            size = Math.max(1, Math.min(50, n.intValue()));
        }

        try {
            Page<MeetingMinutes> meetingPage = meetingRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
            List<MeetingMinutes> meetings = meetingPage.getContent();

            List<Map<String, Object>> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            for (MeetingMinutes m : meetings) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.getId());
                item.put("title", m.getTitle());
                item.put("date", m.getMeetingDate() != null ? m.getMeetingDate().format(fmt) : "");
                item.put("status", m.getStatus());
                items.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", meetingPage.getTotalElements());
            result.put("page", meetingPage.getNumber());
            result.put("items", items);

            return Mono.just(ToolResultBlock.text(toJson(result)));
        } catch (Exception e) {
            log.warn("List meetings failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("获取会议列表异常，请稍后重试"));
        }
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(toJsonValue(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJsonValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (val instanceof Map<?, ?> m) {
            return toJson((Map<String, Object>) m);
        }
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd /Users/ys/IdeaProjects/meeting-agent/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/meeting/agent/ListMeetingsTool.java
git commit -m "feat(agent): add list_meetings tool for browsing meeting list"
```

---

### Task 4: SearchMeetingTitlesTool

**Files:**
- Create: `backend/src/main/java/com/meeting/agent/SearchMeetingTitlesTool.java`

**Interfaces:**
- Consumes: `MeetingMinutesRepository.searchByTitleKeyword(keyword, limit)`
- Produces: AgentTool 名称 `"search_meeting_titles"`

- [ ] **Step 1: 新建 SearchMeetingTitlesTool.java**

```java
package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class SearchMeetingTitlesTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchMeetingTitlesTool.class);

    private final MeetingMinutesRepository meetingRepository;

    public SearchMeetingTitlesTool(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "search_meeting_titles";
    }

    @Override
    public String getDescription() {
        return "通过标题关键词搜索会议。当用户知道会议的大概名称或想找某次特定会议时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "会议标题关键词"
        ));
        properties.put("limit", Map.of(
                "type", "number",
                "description", "返回结果数，默认10，最多50"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("keyword")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String keyword = input.getOrDefault("keyword", "").toString();
        if (keyword.isBlank()) {
            return Mono.just(ToolResultBlock.text("搜索关键词不能为空"));
        }

        int limit = 10;
        if (input.get("limit") instanceof Number n) {
            limit = Math.max(1, Math.min(50, n.intValue()));
        }

        try {
            List<MeetingMinutes> meetings = meetingRepository.searchByTitleKeyword(keyword, limit);
            if (meetings.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到标题包含「" + keyword + "」的会议。"));
            }

            List<Map<String, Object>> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            for (MeetingMinutes m : meetings) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.getId());
                item.put("title", m.getTitle());
                item.put("date", m.getMeetingDate() != null ? m.getMeetingDate().format(fmt) : "");
                item.put("status", m.getStatus());
                items.add(item);
            }

            return Mono.just(ToolResultBlock.text(toJson(items)));
        } catch (Exception e) {
            log.warn("Search meeting titles failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("搜索会议异常，请稍后重试"));
        }
    }

    private String toJson(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            Map<String, Object> item = items.get(i);
            int j = 0;
            for (var entry : item.entrySet()) {
                if (j++ > 0) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object val = entry.getValue();
                if (val instanceof String s) {
                    sb.append("\"").append(escapeJson(s)).append("\"");
                } else {
                    sb.append(val);
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd /Users/ys/IdeaProjects/meeting-agent/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/meeting/agent/SearchMeetingTitlesTool.java
git commit -m "feat(agent): add search_meeting_titles tool for fuzzy title search"
```

---

### Task 5: ChatService 改造 — 注入新工具 + 删除硬编码 RAG

**Files:**
- Modify: `backend/src/main/java/com/meeting/service/ChatService.java`

**Interfaces:**
- Consumes: Task 2/3/4 的 AgentTool 类 + `McpClientBuilder` + `McpClientWrapper`
- Produces: 改造后的 ChatService 构造函数和 streamChat 方法

这是最关键的变更。需要：
1. 在构造函数中注册 3 个新工具
2. 添加 MCP 客户端初始化（带降级）
3. 删除 `buildRagContext()` 方法
4. 修改 `buildEnrichedMessage()` 移除 `ragContext` 参数
5. 修改 `streamChat()` 移除 RAG 调用
6. 更新 SYSTEM_PROMPT

- [ ] **Step 1: 修改 ChatService 构造函数 — 新增依赖注入和 MCP 初始化**

在文件开头的 `@Value` 注入列表中添加：

```java
public ChatService(@Value("${deepseek.api-key:}") String apiKey,
                   @Value("${deepseek.model:deepseek-chat}") String modelName,
                   @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                   @Value("${zhipu.api-key:}") String zhipuApiKey,
                   @Value("${zhipu.model:glm-4v}") String zhipuModel,
                   @Value("${zhipu.url:https://api.open.bigmodel.cn/api/paas/v4}") String zhipuUrl,
                   @Value("${tavily.api-key:}") String tavilyApiKey,
                   DialogueService dialogueService,
                   DialogueRepository dialogueRepository,
                   MeetingMinutesRepository meetingRepository,
                   VectorizationService vectorizationService,
                   UploadToKnowledgeBaseTool uploadToKnowledgeBaseTool,
                   SearchKnowledgeBaseTool searchKnowledgeBaseTool,
                   ListMeetingsTool listMeetingsTool,
                   SearchMeetingTitlesTool searchMeetingTitlesTool) {
    // ... existing field assignments ...

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
```

需要添加的 import：
```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
```

- [ ] **Step 2: 更新 SYSTEM_PROMPT**

将：
```java
private static final String SYSTEM_PROMPT = "你是智能助手，回答简洁准确。";
```

改为：
```java
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
```

- [ ] **Step 3: 删除 `buildRagContext()` 方法**

删除整个方法（约从 `buildRagContext` 到方法结束的 `}`）。

- [ ] **Step 4: 修改 `buildEnrichedMessage()` 方法**

当前签名：
```java
private String buildEnrichedMessage(String ragContext, String fileContext, String userMessage) {
```

改为：
```java
private String buildEnrichedMessage(String fileContext, String userMessage) {
```

方法体中移除所有 `ragContext` 相关的代码：
- 删除 `StringBuilder sb` 中关于 ragContext 的判断和拼接
- 方法逻辑简化为：有 fileContext 时拼接，否则直接返回 userMessage

```java
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
```

- [ ] **Step 5: 修改 `streamChat()` 方法 — 移除 RAG 调用**

在 `streamChat()` 中找到这两行并删除：
```java
String ragContext = buildRagContext(dialogueId, userMessage);
```
和对应的 `ragContext` 参数传递。

将：
```java
String ragContext = buildRagContext(dialogueId, userMessage);

// 3. ZhiPu only for multimodal (images), DeepSeek for all text
if (hasImages) {
    streamChatWithZhipu(dialogueId, userMessage, ragContext, fileBlocks, emitter);
} else {
    streamChatWithDeepSeek(dialogueId, userMessage, ragContext, fileBlocks, emitter);
}
```

改为：
```java
// 3. ZhiPu only for multimodal (images), DeepSeek for all text
if (hasImages) {
    streamChatWithZhipu(dialogueId, userMessage, fileBlocks, emitter);
} else {
    streamChatWithDeepSeek(dialogueId, userMessage, fileBlocks, emitter);
}
```

- [ ] **Step 6: 修改 `streamChatWithDeepSeek()` 方法签名**

移除 `ragContext` 参数：
```java
private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                    List<ContentBlock> fileBlocks,
                                    SseEmitter emitter) {
```

方法体内：
```java
String enriched = buildEnrichedMessage(ragContext, null, userMessage);
```
改为：
```java
String enriched = buildEnrichedMessage(null, userMessage);
```

以及：
```java
String enriched = buildEnrichedMessage(ragContext, fileContext, userMessage);
```
改为：
```java
String enriched = buildEnrichedMessage(fileContext, userMessage);
```

- [ ] **Step 7: 修改 `streamChatWithZhipu()` 方法签名**

移除 `ragContext` 参数：
```java
private void streamChatWithZhipu(Long dialogueId, String userMessage,
                                  List<ContentBlock> fileBlocks,
                                  SseEmitter emitter) {
```

方法体内：
```java
String enriched = buildEnrichedMessage(ragContext, fileContext, userMessage);
```
改为：
```java
String enriched = buildEnrichedMessage(fileContext, userMessage);
```

- [ ] **Step 8: 删除不再需要的 import**

移除：
```java
import com.meeting.config.DeepSeekChatClient;  // 如果之后没有被其他地方引用
```

保留所有其他 import（`MeetingMinutesRepository`, `VectorizationService` 等仍然在文件中其他地方使用）。

- [ ] **Step 9: 验证编译通过**

Run: `cd /Users/ys/IdeaProjects/meeting-agent/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/meeting/service/ChatService.java
git commit -m "refactor(chat): replace hardcoded RAG injection with agent-driven tools, add MCP support"
```

---

### Task 6: Nacos 配置 + Dockerfile 更新

**Files:**
- Modify: `backend/src/main/resources/application.yml` (或 Nacos)
- Modify: `docker/backend/Dockerfile` (或 docker-compose.yml 中后端服务的环境变量)

- [ ] **Step 1: 在 application.yml 中添加 Tavily 配置**

```yaml
tavily:
  api-key: ${TAVILY_API_KEY:}
```

如果 Nacos 已经接管此配置，改为在 meeting-agent.yaml 中添加同样的内容。

- [ ] **Step 2: 更新 Docker 后端 Dockerfile**

在现有 Dockerfile 中添加 Node.js 安装步骤：

```dockerfile
# 在 apt-get install 阶段添加 nodejs npm
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg \
    nodejs \
    npm \
    && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 3: 在 docker-compose.yml 或环境变量中添加 TAVILY_API_KEY**

```yaml
services:
  backend:
    environment:
      - TAVILY_API_KEY=${TAVILY_API_KEY}
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/application.yml docker/ docker-compose.yml
git commit -m "chore(config): add tavily api key config and nodejs for MCP"
```

---

### Task 7: 前端 — agent 行为展示（可选，低优先级）

如果前端需要展示 agent 正在使用工具的状态，可以添加。但 agentScope 的 SSE 流默认会包含 tool_use 事件，前端打字机效果已经能展示。

当前 chat 流使用的是 ChatController `/api/dialogue/{id}/chat`，已经在使用 SSE + 打字机效果，不需要额外前端修改。

- [ ] **Step 1: 确认前端不需要改动**

前端打字机效果通过 SSE `TextBlockDeltaEvent` 驱动，tools 调用在 agentScope 内部处理，结果以文本 delta 返回。前端无感知变化。

- [ ] **Step 2: 验证整体功能**

启动后端服务后：
1. 启动时看日志：`Tavily MCP client initialized successfully` 或 `TAVILY_API_KEY not configured`
2. 在对话中输入"帮我搜一下最近有什么会议" → 验证 agent 调用 `list_meetings`
3. 输入"找一下项目周会的内容" → 验证 agent 调用 `search_meeting_titles` + `search_knowledge_base`
4. 输入"xx技术的最新进展" → 验证 agent 调用 `tavily_search`（如已配置）
5. 确认不再有 `RAG check:` 和 `RAG triggered:` 日志（旧硬编码 RAG 已移除）

---

## 验证清单

1. `mvn compile` 通过
2. 启动后日志没有异常
3. 对话输入"最近有什么会议" → agent 调 `list_meetings`
4. 对话输入"项目周会说了什么" → agent 调 `search_meeting_titles` 找会议 → 再调 `search_knowledge_base` 查内容
5. 对话输入常规问题（与会议无关）→ agent 不调任何搜索工具，直接回答
6. 无需修改前端
