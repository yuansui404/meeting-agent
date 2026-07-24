# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

个人使用的会议纪要智能体：MP4 上传 → MiMo-V2.5-ASR 语音转文字 → DeepSeek 改写润色 → RAG 智能搜索。

## Build & Commands

```bash
# 后端编译
cd backend && mvn compile

# 运行全部测试
cd backend && mvn test

# 运行单个测试类/方法
cd backend && mvn test -Dtest=SessionServiceTest
cd backend && mvn test -Dtest=SessionServiceTest#createSession_ShouldSetSessionId

# 前端
cd frontend && npm start
cd frontend && npm run build

# Docker 全栈启动
cd docker && docker compose up -d

# 本地开发（仅启动基础设施）
cd docker && docker compose up -d postgres redis nacos
cd backend && mvn spring-boot:run
```

## Architecture

Spring Boot 3.2 + Java 21，按业务领域组织为独立模块，每个模块自包含 entity/repository/service。无 MyBatis，纯 JPA + JdbcTemplate。

### Package Structure

```
com.meeting/
├── controller/          # REST API（request record / response VO），统一 ApiResponse<T> 响应
├── conversation/        # 会话领域：Session, Dialogue, Rewrite, Style
│   ├── middleware/      # Agent 中间件：FileContext, ProfileIndex, SearchCheckReminder
│   └── service/         # ChatStreamService（SSE 流式）, DocxExportService, StyleLearningService
├── document/            # 文档 ETL：上传 → 解析 → 分块（ChunkStrategy）→ 向量化
├── knowledgebase/       # 知识库上传 + UploadToKnowledgeBaseTool
├── llm/                 # EmbeddingService（向量化）
├── meeting/             # MeetingDateExtractor
├── retrieval/           # RAG 检索管线（8 阶段）
│   ├── algorithm/       # RrfMerger, TimeDecayScorer, DocumentDeduplicator, EvidenceEvaluator, CitationBuilder
│   └── service/         # HybridSearchService, QueryPlanningService, FullTextSearch, VectorSearch, Reranker
├── transcription/       # MiMo ASR 调用 + 文件处理 + 音频提取
├── user/                # 用户画像读写
├── agent/               # AgentScope Tool 实现（11 个 @Component AgentTool）：SearchDocuments, ListMeetings, CallMiMoAsr, UnderstandImage, ExportDocx 等
├── template/            # 模板管理（TemplateEntity, TemplateController, TemplateService）
├── state/               # PgAgentStateStore（对话状态持久化到 PostgreSQL JSONB）
├── eval/                # EvalRunner（RAG 评估）
├── config/              # @Configuration + @ConfigurationProperties（DeepSeek, Mimo, ZhiPu, Rag, File, Embedding 等）
└── common/              # ApiResponse, BusinessException, GlobalExceptionHandler, SseEventTypes, TtlMdcAdapter, TraceIdFilter
```

### Key Technical Decisions

- **DI**: `@RequiredArgsConstructor` + `final` 字段注入
- **配置外化**: Nacos 管理 API 密钥和业务参数，本地 `application.yml` 仅含基础设施配置。Nacos 配置通过 `POST /actuator/refresh` 热加载
- **Agent 架构**: HarnessAgent（agentscope）主 agent，通过 AGENTS.md 定义路由规则，按意图加载 skill（rewrite-routing / transcription-routing / search-routing）。子 agent 通过 `agent_spawn` 委派，subagent 定义在 `backend/.agentscope/workspace/subagents/`
- **工具组机制**: 工具分为 rewrite / search / file 三组，通过 `reset_equipped_tools` 激活。通用工具（read_profile, export_docx 等）始终可用
- **SSE 流式**: ChatStreamService 使用虚拟线程 + AtomicBoolean 实现前后端生命周期解耦（前端断开只停止推流，大模型继续生成完毕并持久化）
- **SSE 事件协议**: 定义在 `SseEventTypes` — text_delta / thinking / tool_call / tool_result / done / error
- **异常处理**: GlobalExceptionHandler 区分 SSE 请求和普通 REST 请求，SSE 请求直接写事件流而非返回 ResponseEntity
- **MDC 追踪**: TraceIdFilter 注入 traceId，TtlMdcAdapter 支持跨线程传递，用于请求链路追踪
- **向量存储**: MeetingVector 用 `Long meetingId` 裸外键（非 @ManyToOne），因 Hibernate 无法映射 pgvector VECTOR 列，读取走 JdbcTemplate
- **检索管线**: QueryPlan → 全文+向量并行（CompletableFuture）→ RRF 融合（k=60）→ DeepSeek Rerank（batch=10）→ 4 级时间衰减 → 文档级去重 → 低置信度重试 → 相邻 chunk 扩展 + 证据评估
- **测试**: 使用 H2 内存数据库，测试配置中关闭 Reranker（`rag.retrieval.rerank-enabled: false`）
- **JPA JSON 转换器**: `conversation/converter/` 下 JsonListConverter, JsonMapConverter, JsonMetadataConverter 用于 JSONB 列映射
- **AgentScope skill 路由**: 路由规则定义在 `AGENTS.md`，skill 文件在 `backend/.agentscope/workspace/skills/`，支持 `load_skill_through_path` 运行时加载

### Core Business Flows

1. **文件上传转录**: FileProcessingService 保存 → TranscriptionService 异步 ASR → `.transcription.md` 侧车文件
2. **知识库上传**: KnowledgeBaseService → 提取内容 → VectorizationService 分块向量化
3. **RAG 搜索**: HybridSearchService 混合检索（全文 + 向量 + RRF + Rerank + 证据评估）
4. **对话**: ChatController → ChatStreamService → AgentScope（HarnessAgent + skill 路由）SSE 流式
5. **改写**: 通过 `rewrite-routing` skill 路由到 rewrite_agent 子 agent，DeepSeek 流式改写 + 五重校对

## Database

- PostgreSQL + pgvector 扩展
- `meeting_minutes` — 会议元数据 + 转写内容
- `meeting_vectors` — 分块向量（cosine distance 索引）
- `dialogue_sessions` / `dialogue_messages` — 对话会话 + 消息
- JPA `ddl-auto: update`（开发环境）/ `none`（Docker 生产）

## Must Follow

- 完成某项任务后更新 `docs/superpowers/开发计划.md`
- 开发计划没有的任务需要进行添加
- 提交时排除 docs 目录和 scripts/eval/test_cases.json（git commit 不带这些变更）