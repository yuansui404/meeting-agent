# 智能会议助手

基于 LLM Agent 的会议纪要智能体：MP4 上传 → MiMo-V2.5-ASR 语音转文字 → DeepSeek 改写润色 → RAG 混合检索智能搜索。

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 前端 | React 18 + Ant Design 5 + TypeScript |
| 后端 | Spring Boot 3.2 + Java 21 + JPA + JdbcTemplate |
| Agent | AgentScope (HarnessAgent + Subagent 委派) |
| LLM | DeepSeek（对话/改写/Rerank）、ZhiPu GLM-4V（图片理解） |
| ASR | MiMo-V2.5-ASR（语音转文字） |
| 向量存储 | PostgreSQL + pgvector（cosine distance） |
| 配置中心 | Nacos |
| 部署 | Docker Compose |

## 系统架构

```
┌──────────────┐     SSE      ┌───────────────────────────────────────────────┐
│   React 前端  │ ◄─────────── │              Spring Boot 后端                  │
│  Ant Design   │   Streaming  │                                               │
└──────────────┘              │  ┌─────────────┐   ┌───────────────┐          │
                              │  │ ChatController│   │ RewriteService│          │
                              │  └──────┬──────┘   └───────┬───────┘          │
                              │         │                   │                  │
                              │  ┌──────▼──────────────────▼───────┐          │
                              │  │    AgentScope HarnessAgent       │          │
                              │  │    (10 tools + subagent 委派)     │          │
                              │  └──────┬──────────────────────────┘          │
                              │         │                                      │
                              │  ┌──────▼──────────────────────────┐          │
                              │  │     HybridSearchService          │          │
                              │  │  全文+向量 → RRF → Rerank → ...  │          │
                              │  └──────┬──────────────────────────┘          │
                              │         │                                      │
                              │  ┌──────▼──────┐  ┌────────────┐              │
                              │  │ PostgreSQL   │  │   Redis    │              │
                              │  │ + pgvector   │  │            │              │
                              │  └─────────────┘  └────────────┘              │
                              └───────────────────────────────────────────────┘
```

## 核心功能

### 1. Agent 智能体

基于 AgentScope 构建，封装 12 类工具，支持 subagent 动态委派：

| 工具 | 说明 |
|------|------|
| `search_documents` | 混合 RAG 搜索（全文+向量+RRF 融合+rerank），带证据评估和引用 |
| `search_knowledge_base` | 知识库向量相似度搜索 |
| `list_meetings` | 分页查询会议列表 |
| `search_meeting_titles` | 会议标题关键词搜索 |
| `read_profile` / `update_profile` | 用户画像读写 |
| `call_mimo_asr` | MiMo ASR 语音转写 |
| `understand_image` | 多模态图片理解（ZhiPu GLM-4V） |
| `export_docx` | 导出会议纪要 |
| `get_style_examples` | 获取风格范例 |
| `list_templates` | 模板列表查询 |
| `response_check` | 搜索结果检查（低置信度提示用户） |
| Tavily MCP | 联网搜索（MCP 协议接入） |

### 2. RAG 多阶段检索管线

```
QueryPlan（意图分类 DIRECT/REWRITE/DECOMPOSE）
    │
    ▼
全文 + 向量并行检索（CompletableFuture）
    │
    ▼
RRF 分数融合（k=60）
    │
    ▼
DeepSeek LLM 重排序（batch=10, score 0-10）
    │
    ▼
4 级时间衰减（30d×1.2 / 90d×1.0 / 365d×0.8 / >1y×0.5）
    │
    ▼
文档级去重（每文档最多 1 个 chunk）
    │
    ▼
低置信度自动重试（阈值 0.7，改写查询重检）
    │
    ▼
相邻 chunk 扩展 + 证据评估（NONE/WEAK/PARTIAL/SUFFICIENT）
```

### 3. LLM 流式改写管线

- **风格学习**：向量加权排序 `similarity × (1 + 0.2 × priority_score)` 选取历史范例
- **DeepSeek 流式润色**：SSE 实时输出改写结果
- **二次校对**：人名 / 部门 / ICT 术语 / 逻辑一致性 / 错别字五重校验

### 4. 上下文管理

- 滑动窗口压缩：30 条触发，保留最近 10 条
- Cleanable 状态存储：自动剥离文件富文本，防止持久化状态膨胀
- PostgreSQL JSONB 持久化：支持跨会话状态恢复

## 项目结构

```
meeting-agent/
├── backend/                          # Spring Boot 后端
│   └── src/main/java/com/meeting/
│       ├── agent/                    # AgentScope Tool 实现（12 个工具）
│       ├── config/                   # @Configuration + @ConfigurationProperties
│       ├── controller/               # REST API + DTO（request/response）
│       ├── conversation/             # 会话领域：Session, Dialogue, Rewrite, Style
│       ├── document/                 # 文档 ETL：上传 → 解析 → 分块 → 向量化
│       ├── eval/                     # 检索评估（EvalRunner + EvalLlmClient）
│       ├── knowledgebase/            # 知识库上传 + 风格范例管理
│       ├── llm/                      # LLM 基础设施：Embedding, Vectorization
│       ├── meeting/                  # 会议实体 + MeetingDateExtractor
│       ├── retrieval/                # RAG 检索管线（8 阶段）
│       │   ├── algorithm/            # RRF, 时间衰减, 去重, 证据评估, 引用构建
│       │   └── service/              # HybridSearchService, Vector/FullText Search, Reranker
│       ├── state/                    # PgAgentStateStore（状态持久化）
│       ├── template/                 # 模板管理
│       ├── transcription/            # MiMo ASR 调用, 文件处理, 音频提取
│       ├── user/                     # 用户画像 + 记忆存储
│       └── common/                   # ApiResponse, BusinessException, GlobalExceptionHandler
├── frontend/                         # React 前端
│   └── src/
│       ├── components/               # UI 组件
│       └── services/                 # API 调用层
├── docker/                           # Docker Compose 编排
│   ├── docker-compose.yml
│   ├── postgres/                     # PostgreSQL + pgvector 镜像
│   └── backend/                      # 后端 Dockerfile
└── docs/                             # 项目文档
```

## 快速开始

### Docker Compose 一键启动

```bash
cd docker && docker compose up -d
```

- 前端：http://localhost:3000
- 后端：http://localhost:8080
- Nacos：http://localhost:8848

### 本地开发

```bash
# 1. 启动基础设施
cd docker && docker compose up -d postgres redis nacos

# 2. 后端编译运行
cd backend && mvn compile && mvn spring-boot:run

# 3. 前端启动
cd frontend && npm install && npm start
```

### 环境变量（Nacos 管理）

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 |
| `DEEPSEEK_BASE_URL` | DeepSeek API 地址 |
| `ZHIPU_API_KEY` | ZhiPu GLM-4V 密钥（图片理解） |
| `MIMO_ASR_API_KEY` | MiMo ASR 密钥 |
| `MIMO_ASR_BASE_URL` | MiMo ASR API 地址 |

> 本地 application.yml 仅含基础设施配置，API 密钥和业务参数通过 Nacos 外化管理。

## 构建与测试

```bash
# 后端编译
cd backend && mvn compile

# 运行全部测试
cd backend && mvn test

# 运行单个测试类
cd backend && mvn test -Dtest=SessionServiceTest

# 运行单个测试方法
cd backend && mvn test -Dtest=SessionServiceTest#createSession_ShouldSetSessionId

# 前端构建
cd frontend && npm run build
```

## License

MIT
