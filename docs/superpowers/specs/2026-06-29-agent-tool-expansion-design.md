# 会议纪要 Agent 工具扩展设计

## 概述

为会议纪要 Agent 扩展工具调用能力，将当前的"被动预注入 RAG"模式改为"Agent 自主驱动"模式。
新增 3 个 AgentTool 和 1 个 MCP 工具，让 agent 能够自主决定何时检索知识库、浏览会议列表、搜索会议标题和联网搜索。

## 设计原则

1. **Agent 自主决策** — 不再在 ChatService 中硬编码 RAG 上下文注入，让 agent 通过工具调用自主决定
2. **MCP 标准协议** — 联网搜索通过 MCP 接入，工具可插拔可替换
3. **轻量接口** — 参数越少越好，减少 agent 理解负担
4. **复用现有服务** — 所有工具底层都包装已有 service/repository，不重复造轮子

## 架构

```
ChatService
  └─ HarnessAgent (Toolkit)
       ├─ upload_to_knowledge_base    (已有, AgentTool — 不变)
       ├─ search_knowledge_base       (新建, AgentTool)
       ├─ list_meetings               (新建, AgentTool)
       ├─ search_meeting_titles       (新建, AgentTool)
       └─ tavily_search               (新建, MCP Stdio)
                                        └─ npx tavily-mcp (子进程)
```

## 工具定义

### 1. search_knowledge_base

语义搜索知识库中的会议记录内容。

```json
名称: "search_knowledge_base"
描述: "搜索知识库中的会议记录内容。当用户询问会议具体内容、决定、讨论要点等需要查阅历史会议信息时使用。"

参数:
{
  "query": { "type": "string", "description": "搜索关键词，尽量简洁准确" },
  "topK":  { "type": "number", "description": "返回结果数，默认5，最少1，最多20" }
}

返回:
[
  {
    "source": "项目周会",
    "content": "与会人：张三、李四。讨论内容：...",
    "similarity": 0.89,
    "date": "2026-06-19"
  }
]
```

底层服务：`VectorizationService.searchSimilarWithScores()`
实现类：`SearchKnowledgeBaseTool.java`

### 2. list_meetings

按创建时间倒序列出所有会议记录。

```json
名称: "list_meetings"
描述: "浏览会议记录列表。当用户想知道最近有哪些会议、查看会议概览时使用。"

参数:
{
  "page": { "type": "number", "description": "页码，从0开始（默认0）" },
  "size": { "type": "number", "description": "每页条数（默认10，最多50）" }
}

返回:
{
  "total": 42,
  "page": 0,
  "items": [
    { "id": 1, "title": "项目周会", "date": "2026-06-19", "status": "completed" },
    { "id": 2, "title": "需求评审", "date": "2026-06-18", "status": "completed" }
  ]
}
```

底层：`MeetingMinutesRepository` 新增分页查询（按 `created_at DESC`）

### 3. search_meeting_titles

按标题关键词模糊搜索会议记录。

```json
名称: "search_meeting_titles"
描述: "通过标题关键词搜索会议。当用户知道会议的大概名称或想找某次特定会议时使用。"

参数:
{
  "keyword": { "type": "string", "description": "会议标题关键词" },
  "limit":   { "type": "number", "description": "返回结果数，默认10，最多50" }
}

返回:
[
  { "id": 1, "title": "2026-06-19 项目评审会", "date": "2026-06-19", "status": "completed" },
  { "id": 5, "title": "项目启动会",           "date": "2026-06-10", "status": "completed" }
]
```

底层：`MeetingMinutesRepository` 新增 ILIKE 模糊匹配 + `LIMIT` 查询

### 4. tavily_search

通过 Tavily 搜索引擎联网搜索实时信息。

```json
名称: "tavily_search"
描述: "搜索互联网获取实时信息。当用户询问最新资讯、政策法规、技术文档、外部资料等需要联网获取的信息时使用。"

参数:
{
  "query":       { "type": "string", "description": "搜索关键词" },
  "searchDepth": { "type": "string", "description": "搜索深度：basic（快速）或 advanced（深入），默认 basic" },
  "topic":       { "type": "string", "description": "搜索主题：general（通用）或 news（新闻），默认 general" },
  "maxResults":  { "type": "number", "description": "返回结果数，默认5，最多10" }
}
```

实现方式：AgentScope MCP Stdio
- 启动时通过 `McpClientBuilder.stdio("npx", "tavily-mcp")` 拉起子进程
- 通过 `toolkit.registerMcpClient()` 注册到 HarnessAgent
- 环境变量 `TAVILY_API_KEY` 用于认证

### 5. upload_to_knowledge_base

已有工具，不变。

## 关键变更：ChatService

### 1. System Prompt 调整

当前：
```
"你是智能助手，回答简洁准确。"
```

改为：
```
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
```

### 2. 移除硬编码 RAG 预注入

删除以下方法/逻辑：
- `buildRagContext()` — 整个方法
- `buildEnrichedMessage()` 中的 `ragContext` 参数
- `streamChat()` 中调用 `buildRagContext()` 的行

保留：
- `buildFileBlocks()` — 文件附件处理（与 RAG 无关）
- `buildFileTextContext()` — 文件文本内容提取（与 RAG 无关）

### 3. 构造函数变更

```java
// 新增：MCP 客户端初始化
McpClientWrapper tavilyClient = McpClientBuilder
    .stdio("npx", "tavily-mcp")
    .env("TAVILY_API_KEY", tavilyApiKey)
    .buildSync();

Toolkit toolkit = new Toolkit();
toolkit.registerAgentTool(uploadToKnowledgeBaseTool);
toolkit.registerAgentTool(searchKnowledgeBaseTool);
toolkit.registerAgentTool(listMeetingsTool);
toolkit.registerAgentTool(searchMeetingTitlesTool);
toolkit.registerMcpClient(tavilyClient);
```

## 新增文件

### 后端（backend/src/main/java/com/meeting/agent/）

| 文件 | 说明 |
|------|------|
| `SearchKnowledgeBaseTool.java` | search_knowledge_base 工具实现 |
| `ListMeetingsTool.java` | list_meetings 工具实现 |
| `SearchMeetingTitlesTool.java` | search_meeting_titles 工具实现 |

所有工具实现 `io.agentscope.core.tool.AgentTool` 接口。

### 后端（Repository 层）

| 文件 | 变更 |
|------|------|
| `MeetingMinutesRepository.java` | 新增 2 个查询方法 |

具体新增查询：

```java
// 分页查询（按创建时间倒序）
Page<MeetingMinutes> findAllByOrderByCreatedAtDesc(Pageable pageable);

// 标题模糊匹配
@Query(value = "SELECT * FROM meeting_minutes WHERE title ILIKE '%' || :keyword || '%' ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
List<MeetingMinutes> searchByTitleKeyword(@Param("keyword") String keyword, @Param("limit") int limit);
```

## 环境变量

| 变量 | 用途 | 来源 |
|------|------|------|
| `TAVILY_API_KEY` | Tavily 搜索 API 认证 | Nacos 配置中新增 |

在 Nacos 配置中添加：
```yaml
tavily:
  api-key: ${TAVILY_API_KEY:}
```

## 工具注册与配置

在 `ChatService` 中新增 3 个工具 Bean 的构造器注入：

```java
public ChatService(
    ...
    SearchKnowledgeBaseTool searchKnowledgeBaseTool,
    ListMeetingsTool listMeetingsTool,
    SearchMeetingTitlesTool searchMeetingTitlesTool,
    @Value("${tavily.api-key:}") String tavilyApiKey
) {
    this.searchKnowledgeBaseTool = searchKnowledgeBaseTool;
    this.listMeetingsTool = listMeetingsTool;
    this.searchMeetingTitlesTool = searchMeetingTitlesTool;
    // ... MCP init
}
```

## MCP 客户端启动降级

`npx tavily-mcp` 依赖 Node.js 环境。以下情况需要降级处理：

1. **Node.js 不可用**（Docker 镜像中未安装）→ 捕获启动异常，不注册 `tavily_search`，agent 照常运行
2. **TAVILY_API_KEY 未配置** → 不启动 MCP 客户端，不注册工具
3. **npx 拉包失败**（网络问题）→ 超时后放弃，继续启动

实现方式：

```java
McpClientWrapper tavilyClient = null;
if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
    try {
        tavilyClient = McpClientBuilder
            .stdio("npx", "tavily-mcp")
            .env("TAVILY_API_KEY", tavilyApiKey)
            .buildSync();
    } catch (Exception e) {
        log.warn("Tavily MCP client init failed (web search will be unavailable): {}", e.getMessage());
    }
}
if (tavilyClient != null) {
    toolkit.registerMcpClient(tavilyClient);
}
```

## Docker 镜像变更

后端 Docker 镜像需要安装 Node.js（用于 `npx tavily-mcp`）。

在 Dockerfile 中添加：
```dockerfile
# 安装 Node.js for Tavily MCP
RUN apt-get update && apt-get install -y nodejs npm && rm -rf /var/lib/apt/lists/*
```

或者使用更可控的版本：
```dockerfile
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*
```

## 非功能性

- 所有工具同步调用（AgentScope 处理异步编排）
- 搜索超时：知识库搜索 10s，联网搜索 30s
- 错误处理：工具调用失败时 agent 回复"搜索异常，请稍后重试"
- 不需要事务支持（只读操作）
- 不需要额外缓存（后续可加 Redis 缓存层）

---

*设计日期：2026-06-29*
*状态：设计完成*
