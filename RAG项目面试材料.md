# RAG 项目面试材料

> 仅涵盖检索增强生成（RAG）部分，按 STAR 法则梳理。每个要点附代码位置方便翻阅。

---

## 一、简历项目描述（精炼版）

**项目：基于多阶段混合检索的 RAG 智能问答系统**

**技术栈：** Java 21 / Spring Boot 3.2 / PostgreSQL + pgvector / DeepSeek / AgentScope

**项目描述：**
面向企业内部会议文档场景，设计并实现了全链路的检索增强生成（RAG）系统，覆盖文档结构化解构、多路混合检索、质量评估与反馈闭环，支持多轮上下文对话。

**核心贡献：**

1. **多路混合检索管线** — 实现向量检索（pgvector cosine）与全文检索（PostgreSQL tsvector）的 RRF 融合，引入 LLM 驱动的查询改写（DIRECT / REWRITE / DECOMPOSE 策略），以及低置信度自动重试，显著提升召回质量。

2. **结构化文档分块策略** — 设计 speaker-aware + section-type-aware 的自适应分块算法，按发言者切换和语义段落边界（总结/决策/结论）切分文档，支持重叠分块和句边界感知分割，提升 chunk 语义完整性。

3. **RAG 质量保障机制** — 引入证据等级评估（NONE / WEAK / PARTIAL / SUFFICIENT）、相似度阈值过滤、MMR 多样性去重、时间衰减评分和来源引用构建，确保低噪声、高可信的内容注入。

4. **Agent 架构编排** — 基于 AgentScope 框架构建搜索 Agent 和对话管理 Agent，实现检索与生成的解耦；集成 DeepSeek 实现 SSE 流式问答，支持长对话自动摘要压缩。

5. **数据库性能优化** — 使用 pgvector HNSW 索引加速近似检索；通过 PostgreSQL 生成列自动维护 tsvector，结合 trigram 模糊搜索弥补中文 FTS 短板。

---

## 二、STAR 梳理

### 1. 多路混合检索（RRF + Query Planning）

| 要素 | 内容 |
|------|------|
| **Situation** | 单一向量检索在会议文档场景下存在漏召回，用户问 "Q1的销售额" 等模糊问题时效果不佳 |
| **Task** | 设计混合检索方案，融合向量语义和关键词两种信号，并优化用户查询 |
| **Action** | ① 实现 Vector + FTS 双路检索，用 Reciprocal Rank Fusion（RRF）融合排序 ② 引入 QueryPlanningService，由 LLM 自动判断将用户查询改写为检索友好形式 ③ 设置低置信度自动重试（追加扩展关键词），兜底冷门查询 |
| **Result** | 多路信号互补，单点命中率和召回稳定性明显提升；查询改写后模糊问题的检索准确率进一步改善 |

**代码位置：**

| 组件 | 文件 | 关键行 |
|------|------|--------|
| 混合检索主流程 | `backend/.../retrieval/service/HybridSearchService.java` | `search()` 第 40-110 行 |
| RRF 融合算法 | `backend/.../retrieval/algorithm/RrfMerger.java` | `merge()` 第 10-29 行 |
| 向量检索 | `backend/.../retrieval/service/VectorSearchService.java` | `search()` 第 27-69 行 |
| 全文检索 | `backend/.../retrieval/service/FullTextSearchService.java` | `search()` 第 18-51 行 |
| 查询规划 | `backend/.../retrieval/service/QueryPlanningService.java` | `plan()` 第 25-54 行 |
| 低置信度重试 | `backend/.../retrieval/service/HybridSearchService.java` | 第 86-99 行 `if (topScore < threshold)` |
| 相邻片段扩展 | `backend/.../retrieval/service/HybridSearchService.java` | `expandNeighbors()` 第 116-143 行 |

---

### 2. 结构化解构（Structured Chunking）

| 要素 | 内容 |
|------|------|
| **Situation** | 固定长度分块（如 512 字符直接切）破坏语义边界，导致大量无关碎片，影响检索命中质量 |
| **Task** | 设计一种适配会议纪要结构的文档分块策略 |
| **Action** | 实现 StructuralChunkStrategy：① 正则识别 "张伟：" 等发言者切换，自动分割 ② 识别 "总结"、"决策"、"会议结论" 等关键词，标记为 SUMMARY / DECISION 语义块 ③ 在句号/感叹号处切分而非硬截断 ④ 相邻块间 128 字符重叠 |
| **Result** | 每个 chunk 语义完整，chunk 内噪音减少，检索命中率和 LLM 回复相关性同步提升 |

**代码位置：**

| 组件 | 文件 | 关键行 |
|------|------|--------|
| 结构化解构策略 | `backend/.../document/service/StructuralChunkStrategy.java` | `chunk()` 第 25-89 行 |
| 发言者正则 | 同上 | `SPEAKER_PATTERN` 第 16-18 行 |
| 段落关键词正则 | 同上 | `SECTION_PATTERN` 第 20-22 行，switch 第 42-48 行 |
| 句边界分割 | 同上 | `findSentenceBoundary()` 第 100-110 行 |
| 重叠分块 | 同上 | 第 65-71 行（overlap 追加） |
| Chunk 策略接口 | `backend/.../document/service/ChunkStrategy.java` | 接口定义第 8 行 |
| Chunk 配置参数 | `backend/.../config/RagProperties.java` | `Chunk` 内部类第 18-22 行 |
| 分块后处理（入库+向量化） | `backend/.../document/service/ChunkService.java` | `processDocument()` 第 33-74 行 |

---

### 3. RAG 质量保障（Evidence + Threshold + Citation）

| 要素 | 内容 |
|------|------|
| **Situation** | RAG 系统面临 "检索到垃圾就输出垃圾" 的风险，缺乏对内容可信度的判断 |
| **Task** | 构建从检索到生成之间的质量把控环节 |
| **Action** | ① EvidenceEvaluator 根据两路命中情况和来源文档数判定 NONE → WEAK → PARTIAL → SUFFICIENT 四级证据强度 ② 相似度阈值 0.55，低于此直接放弃注入 RAG 上下文，由 LLM 凭自身知识回答 ③ CitationBuilder 构建来源引用（文档名 + 内容预览），注入到 prompt 供 LLM 溯源 ④ 时间衰减评分（30 天内 1.2x，归档 0.5x）区分新老文档 |
| **Result** | 低质量检索结果不会污染 LLM 输出；每次回答可追溯来源，提升可信度 |

**代码位置：**

| 组件 | 文件 | 关键行 |
|------|------|--------|
| 证据等级评估 | `backend/.../retrieval/algorithm/EvidenceEvaluator.java` | `evaluate()` 第 10-22 行 |
| 证据等级枚举 | `backend/.../retrieval/model/EvidenceLevel.java` | 第 3-7 行 |
| 来源引用构建 | `backend/.../retrieval/algorithm/CitationBuilder.java` | `build()` 第 9-23 行 |
| MMR 多样性去重 | `backend/.../retrieval/algorithm/MmrDeduplicator.java` | `deduplicate()` 第 9-24 行 |
| 时间衰减评分 | `backend/.../retrieval/algorithm/TimeDecayScorer.java` | `apply()` 第 14-25 行 |
| 时间衰减配置 | `backend/.../config/RagProperties.java` | `TimeDecay` 第 42-48 行 |
| 相似度阈值过滤 | `backend/.../service/ChatService.java` | `buildRagContext()` 第 366-374 行（`RELEVANCE_THRESHOLD = 0.55`） |
| 证据配置参数 | `backend/.../config/RagProperties.java` | `Evidence` 第 33-38 行 |
| 检索重试兜底 | `backend/.../retrieval/service/HybridSearchService.java` | `generateRetryQuery()` 第 112-114 行 |

---

### 4. Agent 架构与长对话管理

| 要素 | 内容 |
|------|------|
| **Situation** | 搜索、对话、知识库导入等功能耦合，多轮对话携带全部历史导致 token 浪费 |
| **Task** | 解耦功能模块，实现高效的上下文管理 |
| **Action** | ① 基于 AgentScope 拆分为 DialogueAgent（对话管理）和 SearchAgent（搜索编排），SearchGuard 使用 ThreadLocal 防止循环 ② SummaryService 自动检测对话 token 量，超 3000 时调用 LLM 压缩历史并保存压缩快照 |
| **Result** | 功能模块职责清晰，长对话 token 消耗可控且关键信息不丢失 |

**代码位置：**

| 组件 | 文件 | 关键行 |
|------|------|--------|
| 搜索 Agent | `backend/.../agent/SearchAgent.java` | 全文件，第 14-31 行 |
| 对话管理 Agent | `backend/.../agent/DialogueAgent.java` | 全文件，第 15-33 行 |
| 搜索守卫（防循环） | `backend/.../agent/SearchGuard.java` | 全文件，第 3-8 行 |
| SSE 流式对话入口 | `backend/.../conversation/ConversationChatController.java` | `streamChat()` 第 32-87 行 |
| RAG 上下文构建 | `backend/.../service/ChatService.java` | `buildRagContext()` 第 348-411 行 |
| 长对话自动压缩 | `backend/.../conversation/service/SummaryService.java` | `checkAndCompress()` 第 25-66 行 |
| 对话压缩阈值 | `backend/.../config/RagProperties.java` | `Conversation` 第 51-56 行（summaryTrigger = 3000） |
| 会话管理 Service | `backend/.../conversation/service/ConversationService.java` | 全文件 |

---

## 三、技术难点

### 难点 1：PostgreSQL VECTOR 类型与 JPA 兼容

| 难点 | 代码位置 |
|------|----------|
| VECTOR 列定义：`float[]` + `columnDefinition = "VECTOR(1024)"` | `backend/.../document/model/entity/DocumentChunkEntity.java` 第 22 行 |
| 向量查询必须用 native SQL：`<=>` 余弦距离 | `backend/.../document/repository/DocumentChunkRepository.java` 第 19-27 行 |
| 向量写入后 JPA 无法读取 VECTOR 列，查询绕道 JdbcTemplate | `backend/.../retrieval/service/VectorSearchService.java` 第 33 行 `chunkRepository.vectorSearch(...)` |
| 库表定义：VECTOR(1024)、HNSW 索引 | `docker/init-db.sql` 第 87 行（embedding 列）、第 125 行（HNSW 索引） |

> Hibernate 原生不支持 PostgreSQL pgvector 扩展类型，写入靠 `columnDefinition` + `float[]` 字段，查询靠手写 `@Query(nativeQuery = true)`。删除等 DML 操作也用 `JdbcTemplate` 绕过 JPA。

### 难点 2：中文全文检索效果差

| 难点 | 代码位置 |
|------|----------|
| tsvector 使用 `simple` 词典（避免 stemming 破坏中文） | `docker/init-db.sql` 第 135 行，`to_tsvector('simple', ...)` |
| 旧管线 trigram 模糊搜索补充 | `docker/init-db.sql` 第 56-57 行 trigram 索引 |
| tsvector 生成列（数据库自动维护） | `docker/init-db.sql` 第 130-137 行 `GENERATED ALWAYS AS` |
| 旧管线 FTS 查询 | `backend/.../service/SearchService.java` 第 37 行 `fullTextSearch(query)` + 第 41 行 `trigramSearch(query)` |
| 新管线 FTS 查询（直接 SQL） | `backend/.../retrieval/service/FullTextSearchService.java` 第 19-28 行 `plainto_tsquery('simple', ?)` |

> 英文 stemming 会破坏中文词汇，选择 `simple` 配置不做任何分词。trigram 模糊索引对中文单字级别的模糊搜索有效，作为兜底补充。

### 难点 3：HNSW 索引参数调优

| 难点 | 代码位置 |
|------|----------|
| HNSW 索引定义（m=16, ef_construction=200） | `docker/init-db.sql` 第 125 行 |
| 旧管线 IVFFlat 索引（不同精度级别） | `docker/init-db.sql` 第 58 行 |

> HNSW 比 IVFFlat 建索引更慢但查询精度更高。`m=16` 控制每层最大连接数（越大召回越高但索引越大），`ef_construction=200` 控制建索引时的搜索深度。

---

## 四、面试高频追问

### Q1：为什么不直接上 LangChain / LlamaIndex？
> "团队选型时评估过，框架对结构化分块和证据评估这种定制需求支持不够灵活，且 RRF 融合需要精确调参。自研的可控性更强，后续如果要扩展 reranker 或 Graph RAG，自研管线的改造代价更小。缺点是缺失社区生态集成，核心算法如 RRF 其实也是参考了业界标准的成熟方案。"

### Q2：RRF 的 k 怎么定的？
> "k 取 60 是参考了 RRF 原论文和业界实践（Elasticsearch 中也用 60）。k 值越大，排名靠后的项对融合分数的贡献越小，适合向量和 FTS 两路分数分布差异大的场景。实际是通过几组对比测试验证的。"

### Q3：证据等级评估怎么验证的？
> "目前是规则驱动的确定性评估——判断两路是否都有命中、来源文档数是否 ≥ 2。结果会暴露给前端和 LLM。设计思路是：检索侧可解释，LLM 侧可参考。后续可以加 recall 基准测试来量化验证各等级与回答质量的相关性。"

### Q4：chunk size 512 和 overlap 128 怎么确定的？
> "会议纪要的句子平均长度和段落结构决定了这个参数。512 字符大约一段话的篇幅，能容纳一位发言者的一个完整观点。128 的 overlap 约 25%，能覆盖句子边界处的关键信息。参数通过配置暴露，可以根据实际文档分布调整。"

### Q5：这个系统和完整 RAG 生产系统的差距在哪？
> "主要差距在评估体系：目前是组件级别的效果验证，缺少全链路的自动化评测 pipeline（如 RAGAS 等框架的忠实度/相关性打分）。另外 reranker 目前是 NoOp 占位实现，接入 BGE 或 Cohere reranker 会是立竿见影的提升点。其余模块（分块、检索、融合、质量保障）的架构完整性达到了生产级别。"

---

## 五、核心代码文件速查表

```
# RAG 检索核心
backend/src/main/java/com/meeting/retrieval/service/HybridSearchService.java    # 混合检索主流程
backend/src/main/java/com/meeting/retrieval/service/VectorSearchService.java    # 向量检索
backend/src/main/java/com/meeting/retrieval/service/FullTextSearchService.java  # 全文检索
backend/src/main/java/com/meeting/retrieval/service/QueryPlanningService.java   # 查询规划（LLM改写）
backend/src/main/java/com/meeting/retrieval/service/Reranker.java               # 重排序接口
backend/src/main/java/com/meeting/retrieval/service/NoOpReranker.java           # 重排序默认实现

# RAG 算法组件
backend/src/main/java/com/meeting/retrieval/algorithm/RrfMerger.java            # RRF 融合
backend/src/main/java/com/meeting/retrieval/algorithm/MmrDeduplicator.java      # MMR 去重
backend/src/main/java/com/meeting/retrieval/algorithm/TimeDecayScorer.java      # 时间衰减
backend/src/main/java/com/meeting/retrieval/algorithm/EvidenceEvaluator.java    # 证据等级
backend/src/main/java/com/meeting/retrieval/algorithm/CitationBuilder.java      # 引用构建

# 文档分块
backend/src/main/java/com/meeting/document/service/StructuralChunkStrategy.java # 结构化解构
backend/src/main/java/com/meeting/document/service/ChunkStrategy.java           # 分块策略接口
backend/src/main/java/com/meeting/document/service/ChunkService.java            # 分块 + 向量化 调度

# Agent 层
backend/src/main/java/com/meeting/agent/SearchAgent.java                        # 搜索 Agent
backend/src/main/java/com/meeting/agent/DialogueAgent.java                      # 对话管理 Agent
backend/src/main/java/com/meeting/agent/SearchGuard.java                        # 搜索状态守卫

# 对话与生成
backend/src/main/java/com/meeting/service/ChatService.java                      # 流式聊天（RAG上下文注入）
backend/src/main/java/com/meeting/conversation/ConversationChatController.java  # 对话聊天 API
backend/src/main/java/com/meeting/conversation/service/SummaryService.java       # 长对话压缩

# 配置
backend/src/main/java/com/meeting/config/RagProperties.java                     # RAG 全套配置参数
backend/src/main/resources/application.yml                                      # 运行时配置
docker/init-db.sql                                                              # 库表定义 + 索引

# 模型定义
backend/src/main/java/com/meeting/document/model/entity/DocumentEntity.java     # 文档实体
backend/src/main/java/com/meeting/document/model/entity/DocumentChunkEntity.java # 文档块实体
backend/src/main/java/com/meeting/retrieval/model/ChunkResult.java              # 检索结果模型
backend/src/main/java/com/meeting/retrieval/model/EvidenceLevel.java            # 证据等级枚举
backend/src/main/java/com/meeting/document/model/ChunkSegment.java              # 分块后中间模型
```

---

## 六、核心表设计：document_chunk（文档块表）

这是 RAG 链路的**核心表**，一张表承载了向量检索和全文检索两种能力。

### DDL

```sql
CREATE TABLE document_chunk (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content       TEXT NOT NULL,
    embedding     VECTOR(1024),         -- 向量列，存储 1024 维 float 数组
    chunk_index   INT NOT NULL,         -- 文档内的第几个块（从0开始）
    speaker       VARCHAR(100),         -- 发言人姓名
    section_type  VARCHAR(50),          -- STATEMENT / SUMMARY / DECISION
    metadata      TEXT DEFAULT '{}',    -- JSON 格式的额外元数据
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### 索引（三个维度，各司其职）

```sql
-- 1. HNSW 向量索引（用于近似最近邻搜索）
CREATE INDEX idx_chunk_embedding 
    ON document_chunk USING hnsw (embedding vector_cosine_ops) 
    WITH (m = 16, ef_construction = 200);

-- 2. GIN 全文索引（用于关键词匹配）
--    content_tsv 是自动生成的 tsvector 列
CREATE INDEX idx_chunk_content_tsv 
    ON document_chunk USING gin (content_tsv);

-- 3. B-tree 普通索引（用于按文档查询）
CREATE INDEX idx_chunk_document_id 
    ON document_chunk(document_id);
```

### 自动生成的全文索引列

```sql
-- content_tsv 列由 content 字段自动生成，不需要应用层维护
ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;
```

### 和 MySQL 表一样吗？和独立向量数据库的区别？

**和 MySQL 一样**：因为它本质上就是一张关系表，`id BIGSERIAL`、`content TEXT`、`speaker VARCHAR(100)` 都是标准 SQL 类型。只是多了一列 `VECTOR(1024)`，这是 pgvector 插件添加的列类型，底层存的是一个 float 数组。

**和独立向量数据库（Milvus/Pinecone）的区别**：

| 对比项 | PostgreSQL + pgvector | Milvus |
|--------|----------------------|--------|
| 表结构 | 标准 SQL 表 + VECTOR 列 | Collection（没有 TEXT/INT 等标准类型）|
| 多字段过滤 | 直接 WHERE speaker='张三' | 复杂，部分字段需额外索引 |
| 全文检索 | 同一张表 content_tsv 列，GIN 索引 | 不支持，需要搭 ES |
| 事务/外键 | 支持 | 不支持 |
| JOIN | 支持 | 不支持 |

所以用 pgvector 最大的好处是：**一张表搞定向量检索 + 全文检索 + 结构化过滤**，不需要搭 Milvus + ES 两套系统，运维成本和数据同步复杂度大幅降低。

### 表设计特点

| 特性 | 说明 |
|------|------|
| **一张表承载双通道** | 不用 ES，不用 Milvus，PostgreSQL 一把梭 |
| **VECTOR 是 pgvector 插件提供的列类型** | 底层存的是 float 数组，不是独立的向量数据库 |
| **HNSW 索引** | 比 IVFFlat 召回更准、查询更快，构建时稍慢 |
| **自动生成 tsvector** | GENERATED ALWAYS AS ... STORED，写 content 时自动分词 |
| **结构化字段保留** | speaker、section_type 可以直接加 WHERE 条件过滤 |
| **1024 维** | 匹配智谱 embedding-2 模型维度；切换 OpenAI ada-002 需改成 1536 |
