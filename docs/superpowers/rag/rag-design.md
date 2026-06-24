# Agent RAG 系统设计文档

> 日期：2026-06-22
> 基于 DD_Rag 项目设计思路，从零构建具备 RAG 能力的 AI Agent 系统
>
> **范围声明**：本文档仅覆盖 RAG 相关的文档管理、知识库检索、Agent 问答能力。
> 音频转写（FunASR）、视频上传等能力不在本文档范围内，与 RAG 管线并行演进，互不依赖。

---

## 一、技术栈

| 层级 | 技术选型 |
|---|---|
| 后端框架 | Spring Boot |
| ORM | Spring Data **JPA** + Hibernate（`ddl-auto: update` / 显式 SQL） |
| AI 模型 | DeepSeek-V4-Flash（1M 上下文窗口） |
| 向量数据库 | PostgreSQL + pgvector |
| 文档解析 | 智谱多模态模型（GLM-4V） |
| Agent 框架 | AgentScope **Harness**（`HarnessAgent`，流式） |
| 搜索引擎 | PostgreSQL tsvector 全文检索（无需 ES） |
| 配置中心 | **Nacos** 2.3.0 |
| 缓存 | Redis 7（备用，环境就绪） |
| 文件存储 | 本地文件系统 |
| 部署 | Docker |

> **ORM 选型说明**：使用 JPA 而非 MyBatis-Plus。项目仅使用 PostgreSQL，无需 MyBatis 的方言适配能力；JPA 的 `@Entity` + `Repository` 模式足够覆盖单表 CRUD + 原生查询（向量检索）。<br>
> **注意：设计文档中的 SQL DDL 和 Entity 定义仅供参考，实际以 JPA `@Entity` 注解 + `spring.jpa.hibernate.ddl-auto` 或 `init-db.sql` 为准。

---

## 二、核心能力总览

```
文档上传 → ETL → 双索引入库 → 用户提问 → Query 规划
→ 混合检索（向量+全文+RRF+Rerank）→ 证据约束 → Agent 回答
```

全链路 15 个设计要点：

1. 结构化感知切分
2. Query 规划（DIRECT / REWRITE / DECOMPOSE）
3. 混合检索 + RRF 融合
4. 可插拔 Rerank
5. 时间感知召回加权
6. 低置信度自动重查
7. MMR 多样性去重
8. 邻居扩窗
9. 四级证据约束
10. 引用去重汇聚
11. 防重复工具调用
12. Token 感知滚动摘要
13. 用户反馈自适应调参
14. 离线评估体系
15. 全链路 traceId 日志追踪

### 算法抽象架构

检索链路中的可复用算法应封装为**无状态工具类**，与 ORM/Spring 解耦：

| 算法 | 类名 | 说明 |
|---|---|---|
| RRF 融合排序 | `RrfMerger` | 入参两个 List<ChunkResult> + k，出参融合排序结果 |
| 时间衰减加权 | `TimeDecayScorer` | 入参 meeting_date + 配置，出参权重系数 |
| MMR 多样性去重 | `MmrDeduplicator` | 入参结果列表 + topN，出参去重后列表 |
| 四级证据评估 | `EvidenceEvaluator` | 入参检索统计，出参 EvidenceLevel 枚举 |
| 引用去重汇聚 | `CitationBuilder` | 入参结果列表，出参引用列表 |

这些工具类放在 `retrieval` 包下，不依赖 Spring Bean，可直接单元测试。

---

## 三、数据模型（4 张核心表）

> **实现说明**：以下 DDL 仅供参考，实际使用 JPA `@Entity` 注解映射。
> 表创建方式：项目使用 `init-db.sql` 脚本（已存在）或 JPA `ddl-auto: update`。不引入 Flyway。
> JPA 的 `spring.jpa.hibernate.ddl-auto=update` 可以自动建表，但建议保留 `init-db.sql` 以明确控制 pgvector 索引、tsvector 列等 DDL。

### 3.1 文档表（document）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | 主键 |
| title | VARCHAR(255) | 文档标题 |
| file_type | VARCHAR(20) | pdf / doc |
| file_path | VARCHAR(500) | 本地存储路径 |
| file_size | BIGINT | 文件大小 |
| meeting_date | DATE | 会议实际日期 |
| status | VARCHAR(20) | UPLOADED → PROCESSING → COMPLETED / FAILED |
| chunk_count | INT | 切分后 Chunk 数 |
| created_at | TIMESTAMP | — |
| updated_at | TIMESTAMP | — |

### 3.2 文档块表（document_chunk）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | 主键 |
| document_id | BIGINT FK | 所属文档 |
| content | TEXT | Chunk 文本内容 |
| embedding | VECTOR(1536) | DeepSeek 向量化（1536 维） |
| chunk_index | INT | 文档内第几个块 |
| speaker | VARCHAR(100) | 发言人（会议纪要提取） |
| section_type | VARCHAR(50) | STATEMENT / SUMMARY / DECISION |
| metadata | JSONB | 额外元数据（页码、标题等） |
| created_at | TIMESTAMP | — |

索引：`document_id`, `embedding`(HNSW 索引), `tsvector(content)`

### 3.3 会话表（conversation）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | 主键 |
| title | VARCHAR(200) | 对话标题 |
| status | VARCHAR(20) | ACTIVE / ARCHIVED |
| context_summary | TEXT | 历史对话压缩摘要 |
| compression_history | JSONB | 压缩历史记录 |
| message_count | INT | 消息数 |
| created_at | TIMESTAMP | — |
| updated_at | TIMESTAMP | — |

### 3.4 消息表（message）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | 主键 |
| conversation_id | BIGINT FK | 所属会话 |
| role | VARCHAR(20) | USER / ASSISTANT / TOOL |
| content | TEXT | 消息内容 |
| trace_id | VARCHAR(64) | 全链路追踪 ID |
| metadata | JSONB | 引用来源、Token 消耗、用户反馈等 |
| created_at | TIMESTAMP | — |

**metadata 结构：**
```json
{
  "citations": [{"doc_id": 1, "chunk_index": 3, "file_name": "周会纪要.pdf"}],
  "feedback": "up" | "down" | null,
  "token_usage": {"prompt": 1230, "completion": 256, "total": 1486, "cost": 0.0004},
  "evidence_level": "SUFFICIENT"
}
```

---

## 四、文档 ETL 流程

```
用户上传 PDF/Doc
    ↓
1. 智谱多模态模型解析文档（转图片 → 视觉提取文本）
2. 结构化感知切分
   - 识别发言人（人名+冒号模式）
   - 按说话轮次保留语义完整性
   - 识别总结/决策标记
   - 重叠窗口（overlap=128 字符）
   ↓
3. DeepSeek Embeddings 向量化（1536 维）
4. 向量写入 pgvector + tsvector 全文索引
   ↓
文档状态置 COMPLETED / FAILED
```

**结构化切分策略（会议纪要场景）：**

```
原始文本：
张三：服务器预算需要200万
李四：我同意，成本在可控范围
[总结] 服务器采购预算200万批准通过
[决策] 1.张三周三前出方案

切分结果：
Chunk1: "[发言人]张三：服务器预算需要200万 [overlap]李四：我同意..."
Chunk2: "[发言人]李四：我同意，成本在可控范围 [overlap][总结]..."
Chunk3: "[总结]服务器采购预算200万批准通过 [决策]1.张三周三前出方案"
```

---

## 五、检索链路

### 5.1 完整检索流程

```
用户问题
    ↓
Query 规划 ──→ DIRECT（原样检索）
             → REWRITE（LLM 改写后检索）
             → DECOMPOSE（拆解为子问题分别检索后合并）
    ↓
并行检索 → pgvector 向量检索（cosine 距离，TopK=20）
         → PostgreSQL tsvector 全文检索（TopK=20）
    ↓
RRF 融合排序（k=60，可配置）
    ↓
↓ [可选] Rerank 精排（DashScope gte-rerank，开关控制）
    ↓
时间衰减加权（30天×1.2 / 一年以上×0.5 / 可配置）
    ↓
MMR 多样性去重（避免同文档相邻段落垄断 TopN）
    ↓
低置信度自动重查（最大相似度<阈值时，LLM 改写 query 后二次检索）
    ↓
邻居扩窗（命中 Chunk 向前后各扩展一个 Chunk）
    ↓
四级证据约束过滤
    ↓
引用去重汇聚
    ↓
返回 Agent
```

### 5.2 RRF 融合公式

```
Score(d) = 1/(k + rank_vector) + 1/(k + rank_fts)

k = 60（默认，可配置）
rank_vector = 向量检索中的排名
rank_fts = 全文检索中的排名
```

### 5.3 时间衰减加权

```
days_old = 今天 - meeting_date

if days_old <= 30:    time_factor = 1.2
elif days_old <= 90:  time_factor = 1.0
elif days_old <= 365: time_factor = 0.8
else:                  time_factor = 0.5

最终得分 = RRF_Score × time_factor
```

### 5.4 Agent 识别时间意图

Agent 在调用 `kb_search` 时，如果用户问题包含时间限定词（"最近"、"上个月"、"去年Q3"），自动在参数中携带 `time_range`，检索层转为 WHERE 条件过滤 `meeting_date`。

---

## 六、证据约束设计

### 6.1 四级证据等级

| 等级 | 条件 | 回答行为 |
|---|---|---|
| NONE | 未检索到任何相关文档 | 直接拒答："知识库中未找到相关信息" |
| WEAK | 仅单检索通道命中 | "依据有限，以下信息仅供参考" |
| PARTIAL | 单通道 + 多文档命中 | "基于部分检索到信息" |
| SUFFICIENT | 双通道命中 + 多文档 | 正常回答 + 引用标注 |

### 6.2 Prompt 约束

```
你是一个基于知识库的问答助手。

约束：
1. 必须基于以下检索到的文档内容回答问题
2. 如果检索结果不相关，请如实回答"知识库中未找到相关信息"
3. 不要使用常识补充回答
4. 关键陈述末尾附【来源:X】标注

当前证据等级：{evidence_level}
{对应的回答指令}
```

### 6.3 引用溯源格式

```json
{
  "answer": "服务器采购预算已批准200万【来源:1】",
  "citations": [
    {"source_id": 1, "document": "周会纪要.pdf", "content": "批准服务器采购预算200万"}
  ]
}
```

前端按 fileName 去重展示，点击展开查看具体段落。

---

## 七、Agent 集成设计

### 7.1 架构方案：AgentScope Harness

使用 HarnessAgent（当前项目标准），而非 @AgentTool 注解方式。

```java
// 初始化 Agent（与现有项目一致）
OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(apiKey)
    .modelName(modelName)
    .baseUrl(apiUrl)
    .formatter(new DeepSeekFormatter())
    .stream(true)
    .build();

HarnessAgent agent = HarnessAgent.builder()
    .name("MeetingAssistant")
    .sysPrompt(SYSTEM_PROMPT)
    .model(model)
    .toolkit(new Toolkit())
    .disableMemoryHooks()
    .disableFilesystemTools()
    .build();
```

### 7.2 RAG 上下文注入（非 Tool 调用）

HarnessAgent 不支持自定义 @AgentTool，因此采用**预检索 + 上下文注入**策略：

```
用户消息
    ↓
ChatOrchestrator 拦截消息
    ↓
调用 HybridSearchService 检索知识库
    ↓
将检索结果拼入 System Prompt 或 User Message 上下文
    ↓
发送给 HarnessAgent.streamEvents() 流式生成回答
    ↓
SSE 推送到前端
```

关键代码示意：

```java
// ChatService / ChatOrchestrator 中
String ragContext = hybridSearchService.search(userMessage, 5);
UserMessage msg = new UserMessage(
    "以下是知识库中相关的会议内容参考：\n\n" + ragContext + "\n\n用户问题：" + userMessage
);

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("dialogue-" + dialogueId)
    .build();

agent.streamEvents(msg, ctx)
    .doOnNext(event -> { /* SSE 推送 */ })
    .blockLast();
```

### 7.3 防重复检索

同一轮对话中应避免多次检索相同知识库：

```java
// 单次请求级别标记
public class SearchGuard {
    private static final ThreadLocal<Boolean> SEARCHED = ThreadLocal.withInitial(() -> false);
    public static boolean hasSearched() { return SEARCHED.get(); }
    public static void markSearched() { SEARCHED.set(true); }
    public static void reset() { SEARCHED.remove(); }
}
```

### 7.4 Tool 返回格式（供 Agent 消费的检索结果文本）

```
找到以下相关信息（按相关性排列）：

[1] 来源：周会纪要.pdf（第3段）
内容：服务器采购预算已批准200万
相关性：高

[2] 来源：周会纪要.pdf（第7段）
内容：张三负责周三前出执行方案
相关性：中
```

---

## 八、会话管理

### 8.1 多会话架构

```
POST /api/conversation          # 创建新会话
GET  /api/conversation          # 会话列表（按更新时间倒序）
DELETE /api/conversation/{id}   # 删除会话

POST /api/chat                  # 发送消息（SSE 流式）
GET  /api/conversation/{id}/messages  # 查看历史消息
```

### 8.2 Token 预算分配（32K）

```
系统 Prompt（角色+约束）        ≈ 500 tokens
会话摘要（压缩后）              ≈ 2000 tokens
最近 N 条原始消息（≈10轮）      ≈ 4000 tokens
kb_search 检索结果（Top5）     ≈ 3000 tokens
用户当前问题                   ≈ 1000 tokens
Agent 推理和回答预留            ≈ 20000 tokens
合计                          ≈ 32000 tokens
```

### 8.3 Token 感知滚动摘要

**触发条件：** 每次构建上下文时实时计算预估 token，接近阈值（如 3000）时触发压缩。

**压缩过程：**
```
旧摘要 + 已超过阈值的原始消息 → 调 DeepSeek 压缩 → 新摘要
    ↓
存入 conversation.context_summary
compression_history 追加记录
```

**恢复时的上下文构建：**
```
context_summary（摘要已压缩的历史）
+ 最近 N 条原始消息（从 message 表取）
+ 当前问题
```

**用户的页面展示不受影响：** message 表永久保留所有原始消息，前端直接查表原样展示。

### 8.4 压缩历史记录

```json
[
  {
    "version": 1,
    "triggered_at": "2026-06-22T10:30:00",
    "tokens_before": 3200,
    "tokens_after": 450,
    "message_range": [1, 10]
  }
]
```

---

## 九、质量保障

### 9.1 离线检索评估

**测试集格式（50 条）：**
```json
[
  {"query": "服务器采购预算批了吗", "expected_doc": "周会纪要.pdf"},
  {"query": "物流方案谁负责", "expected_doc": "周会纪要.pdf"}
]
```

**评估指标：**
- **Hit Rate**：TopN 中包含 expected_doc 的比例
- **MRR**：正确答案排位的倒数均值

**使用场景：** 修改切分参数、检索参数、Rerank 开关后，跑测试集量化收益。

### 9.2 用户反馈自适应调参

| 行为 | 系统响应 |
|---|---|
| 用户踩了一次 | 该会话证据阈值 ε 提升 0.05（如 0.7→0.75），RRF k 值调大 |
| 用户连续踩 | 追加 Prompt "请保守回答，不确定就说不知道" |
| 用户点了赞 | 逐步恢复默认参数 |

### 9.3 Token 统计

每次消息记录 `token_usage` 到 message.metadata，支持按会话/全局汇总。

---

## 十、错误处理

| 场景 | 处理方式 | 用户感知 |
|---|---|---|
| DeepSeek API 超时 | catch 超时异常，返回特定错误码 | 前端提示"处理超时，请重新发送" |
| 文档解析失败 | ETL 环节 catch，文档状态置 FAILED | 上传列表显示"解析失败，请重新上传" |
| API 限速（429） | 指数退避重试 3 次 → 仍失败则返回 | 前端提示"当前处理繁忙，请稍后再试" |
| 文档上传 | 前端分片 + 进度上报 | 实时百分比进度条 |

---

## 十一、日志追踪

### 11.1 traceId 全链路追踪

```
Filter 拦截每次请求 → 生成 UUID traceId → 放入 MDC
→ 日志格式: [2026-06-22 10:30:00] [traceId=abc-123] [layer=RETRIEVAL] ...
→ 响应头返回 traceId
```

### 11.2 日志埋点

| Layer | 事件 | 说明 |
|---|---|---|
| API | RECEIVE_MESSAGE | 收到用户消息 |
| AGENT | TOOL_INVOKED | Agent 调 kb_search |
| RETRIEVAL | HYBRID_SEARCH | 混合检索完成 |
| RETRIEVAL | RERANK_COMPLETED | Rerank 完成 |
| RETRIEVAL | EVIDENCE_FILTERED | 证据过滤结果 |
| AGENT | RESPONSE_COMPLETED | Agent 生成完毕 |

### 11.3 排查方式

```
用户反馈问题 → 查到 conversation_id → 关联 traceId
→ grep "traceId=abc-123" app.log → 看到完整的请求链路
```

---

## 十二、部署方案

Docker + docker-compose：

```
app（Spring Boot jar）
postgres（含 pgvector 扩展）
nacos（Nacos 2.3.0 配置中心）
redis（Redis 7 缓存，当前为环境就绪）
```

## 十二（B） 配置中心（Nacos）

当前项目已接入 Nacos 配置中心（`spring-cloud-starter-alibaba-nacos-config`）。

**配置策略：**

| 配置项 | 存放位置 | 理由 |
|---|---|---|
| 数据库连接 | Nacos (`meeting-agent.yaml`) | 环境差异大，需热更新 |
| API Key（DeepSeek/Zhipu） | Nacos | 敏感配置 |
| FunASR URL | Nacos | 环境差异 |
| **RAG 参数**（chunk size / topK / rrf_k） | **`application.yml`** 本地配置 | 开发调试频繁变动，不值得推送 Nacos |
| **非敏感默认值** | `application.yml` | 本地开发友好 |

**本地 `application.yml` 中 RAG 配置区域示意：**

```yaml
rag:
  chunk:
    strategy: structural
    size: 512
    overlap: 128
  retrieval:
    vector_topk: 20
    fts_topk: 20
    rrf_k: 60
  evidence:
    threshold: 0.7
```

Nacos 中 `meeting-agent.yaml` 的配置会覆盖 `application.yml` 的公共部分（DB、API Key），RAG 专属参数留在本地。

## 十二（C） Redis 缓存基础设施

Redis 已存在于 docker-compose 中，当前暂不接入 RAG 缓存逻辑，**但保留环境为后续优化做准备**。

| 阶段 | Redis 用途 | 优先级 |
|---|---|---|
| 当前 | 环境就绪，无业务使用 | — |
| 后续 | 向量检索结果缓存（相同 query 避免重复 embedding） | 低 |
| 后续 | 文档 ETL 任务队列 | 低 |
| 后续 | 会话上下文缓存 | 低 |

---

## 十三、配置管理层

### 13.1 配置分层策略

| 层级 | 位置 | 内容 | 加载方式 |
|---|---|---|---|
| 公共配置 | Nacos `meeting-agent.yaml` | 数据库、API Key、FunASR | `spring-cloud-starter-alibaba-nacos-config` 自动拉取 |
| RAG 参数 | `application.yml` local | chunk/retrieval/evidence 参数 | `@ConfigurationProperties(prefix="rag")` 或 `@Value` |
| 环境覆盖 | `application-docker.yml` | Docker 环境的 Datasource URL | Spring profile |

### 13.2 RAG 本地配置示例

```yaml
rag:
  chunk:
    strategy: structural          # fixed | recursive | structural
    size: 512
    overlap: 128
  retrieval:
    vector_topk: 20
    fts_topk: 20
    rrf_k: 60
    rerank_enabled: false
    rerank_topk: 5
  evidence:
    threshold: 0.7
    adaptive: true
    adaptive_step: 0.05
    adaptive_max: 0.85
  time_decay:
    enabled: true
    recent_days: 30
    recent_weight: 1.2
    normal_weight: 1.0
    old_weight: 0.8
    archive_weight: 0.5
  conversation:
    summary_trigger: 3000
    max_visible_messages: 10
    max_context_tokens: 32000
```

> DeepSeek API Key、Model 等配置不在 `application.yml` 中硬编码，统一在 Nacos 中配置。RAG 参数的 `deepseek.*` 仅保留本地开发时的 fallback 默认值。

---

## 十四、验收方法

### 14.1 文档上传与 ETL

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 上传 PDF 文档 | 状态流转 UPLOADED → PROCESSING → COMPLETED | 查 document 表状态和 chunk_count |
| 上传 Doc 文档 | 同上 | 同上 |
| 上传非受支持格式 | 返回错误提示 | 接口返回 400 |
| 文档解析失败 | 状态置 FAILED，用户可重新上传 | 查 document 表状态 |
| 切分效果验证 | 会议纪要按发言人轮次切分，总结/决策标记独立成块 | 查 document_chunk 表 content 和 section_type |
| 向量化入库 | 每个 chunk 的 embedding 字段非空 | 查 document_chunk 表 |

### 14.2 检索质量

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 向量检索 | 语义相似内容命中 | 手动输入测试问题 |
| 全文检索 | 精确关键词命中（如人名"张三"） | 手动输入含人名的查询 |
| RRF 融合 | 排序合理，双通道命中者优先 | 查看检索日志中的排名 |
| 时间衰减 | 新文档权重高于旧文档 | 上传两份不同日期的文档，查排序 |
| 低置信度重查 | 首次检索分数低时自动改写重查 | 查看日志中的重查记录 |
| MMR 去重 | TopN 结果来自不同文档区域 | 查看检索结果中的 doc_id 分布 |
| Rerank（开启后） | 精度高于不开启 | 跑 50 条测试集对比 MRR |
| 离线评估脚本 | 输出 Hit Rate 和 MRR | 运行脚本查看输出 |

### 14.3 Agent 对话

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 知识库问题触发检索 | Agent 调用 kb_search，返回带引用的回答 | 查看日志 TOOL_INVOKED 事件 |
| 闲聊不触发检索 | Agent 正常闲聊，不调 kb_search | 查看日志无 TOOL_INVOKED |
| 拒答场景 | 知识库无相关内容时返回拒答提示 | 提问无关内容 |
| 引用溯源 | 回答中带【来源:X】且 citations 非空 | 查看返回结果中的 citations |
| 防重复工具调用 | 同次推理不重复检索 | 查看日志仅一次 TOOL_INVOKED |
| SSE 流式 | 回答逐字输出 | 前端观察 |
| 证据等级 NONE | 直接拒答 | 测试无相关文档的问题 |
| 证据等级 WEAK | "依据有限，仅供参考" | 测试仅有单通道命中的问题 |
| 证据等级 SUFFICIENT | 正常回答 + 引用 | 测试有充分文档的问题 |

### 14.4 会话管理

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 创建新会话 | 返回 conversation_id | 调用 POST /api/conversation |
| 会话列表 | 按更新时间倒序排列 | 调用 GET /api/conversation |
| 多会话独立 | 不同会话上下文互不干扰 | 两个会话分别提问，验证上下文隔离 |
| 历史消息 | 查看完整对话历史 | 调用 GET /api/conversation/{id}/messages |
| 恢复会话继续聊天 | 加载上下文后正常回答，能理解前文 | 开启会话，继续提问引用前文的问题 |
| 长对话（超过摘要阈值） | 触发摘要压缩，对话能正常继续 | 持续对话 10+ 轮后查看 context_summary |
| 压缩历史 | compression_history 有记录 | 查 conversation 表 |
| 页面展示不受压缩影响 | 所有原始消息完整显示 | 前端页面查看历史消息 |

### 14.5 用户反馈

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 点赞 | metadata.feedback = "up" | 查 message 表 |
| 点踩 | metadata.feedback = "down"，后续回答更保守 | 点踩后继续提问 |
| 多次点踩 | 证据阈值提升，拒答率增加 | 连续踩 3+ 次后测试 |

### 14.6 错误处理

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| DeepSeek API 不可用 | 返回超时错误，前端可重试 | 模拟 API 故障 |
| 文档解析失败 | 状态 FAILED，前端显示重新上传按钮 | 上传损坏文件 |
| 上传进度 | 实时百分比更新 | 前端观察上传过程 |
| 限速处理 | 返回繁忙提示 | 模拟 429 响应 |

### 14.7 日志与监控

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| traceId 生成 | 每次请求携带 traceId | 查看日志和响应头 |
| 全链路日志 | 完整记录 API → AGENT → RETRIEVAL → PERSISTENCE | grep traceId 查看完整链路 |
| Token 统计 | 每次消息记录 token_usage | 查 message.metadata |
| 按会话统计 Token | 汇总某个会话的 token 消耗 | 写汇总查询 |

### 14.8 配置验证

| 测试项 | 预期结果 | 验证方式 |
|---|---|---|
| 修改 rrf_k | RRF 排序结果随之变化 | 改配置后重跑测试集 |
| 修改 evidence.threshold | 拒答率随之变化 | 改配置后测试 |
| 开启/关闭 Rerank | 检索精度变化 | 改配置后对比测试集 |
| 修改时间衰减权重 | 排序结果变化 | 改配置后测试 |
| 配置热加载（如有） | 不重启生效 | 改配置后直接验证 |

---

## 十五、简历亮点速览

1. **结构化感知切分**：针对会议纪要按发言人轮次保留语义完整性
2. **Query 规划**：LLM 对问题做 DIRECT/REWRITE/DECOMPOSE 预处理
3. **混合检索 + RRF**：向量+全文检索用排名融合替代分数加权
4. **可插拔 Rerank**：预留精排接口可一键开启
5. **时间感知加权**：按会议日期做衰减加权，近 30 天文档优先
6. **低置信度重查**：首次检索分低时 LLM 改写 query 二次检索
7. **MMR 多样性去重**：避免同文档相邻段落垄断 TopN
8. **邻居扩窗**：命中 Chunk 前后扩展一个 Chunk 提供完整上下文
9. **四级证据约束**：NONE/WEAK/PARTIAL/SUFFICIENT 分级回答策略
10. **引用去重汇聚**：同文件只展示一次引用
11. **防重复工具调用**：Agent 单次推理不重复检索
12. **Token 感知滚动摘要**：按 Token 用量触发压缩，支持超长对话恢复
13. **用户反馈自适应**：踩/赞直接影响会话层检索参数
14. **离线评估体系**：Hit Rate + MRR 双指标量化优化收益
15. **全链路 traceId 追踪**：MDC 日志贯穿请求全流程
