# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

个人使用的会议纪要智能体：MP4 上传 → MiMo-V2.5-ASR 语音转文字 → DeepSeek 改写润色 → RAG 智能搜索。

## Build & Run Commands

```bash
# 后端编译
cd backend && mvn compile

# 运行全部测试
cd backend && mvn test

# 运行单个测试类
cd backend && mvn test -Dtest=SessionServiceTest

# 运行单个测试方法
cd backend && mvn test -Dtest=SessionServiceTest#createSession_ShouldSetSessionId

# 前端启动
cd frontend && npm start

# Docker 全栈启动
cd docker && docker compose up -d
```

## Architecture

Spring Boot 3.x + Java 21。后端按业务领域组织为独立模块，每个模块自包含 entity/repository/service：

```
com.meeting/
├── controller/        # REST API + DTO（request record / response VO）
├── conversation/      # 会话领域：Session, Dialogue, Rewrite, Style
│   ├── converter/     # JPA JSON ↔ Java 类型转换器
│   ├── model/entity/  # SessionEntity, DialogueMessageEntity, RewriteResult/Feedback
│   ├── repository/
│   └── service/       # ChatService, SessionService, RewriteService 等
├── document/          # 文档 ETL：上传 → 解析 → 分块 → 向量化
├── knowledgebase/     # 知识库上传 + 风格范例管理
├── llm/               # LLM 基础设施：Embedding, Vectorization, IntentClassifier, QueryRewriter
├── meeting/           # 会议实体 + MeetingDateExtractor
├── retrieval/         # RAG 检索管线
│   ├── algorithm/     # RRF 融合, 时间衰减, 去重, 证据评估, 引用构建（@Component Bean）
│   └── service/       # HybridSearchService, Vector/FullText Search, Reranker
├── transcription/     # 语音转文字：MiMo ASR 调用, 文件处理, 音频提取
├── user/              # 用户画像 + 记忆存储
├── agent/             # AgentScope Tool 实现（9 个工具）
├── state/             # PgAgentStateStore（对话状态持久化到 PostgreSQL）
├── config/            # @Configuration + @ConfigurationProperties
└── common/            # ApiResponse, BusinessException, GlobalExceptionHandler, SseEventTypes
```

### Key Technical Decisions

- **DI**: `@RequiredArgsConstructor` + `final` 字段注入
- **配置外化**: Nacos 管理 API 密钥和业务参数，本地 application.yml 仅含基础设施配置
- **Agent 架构**: HarnessAgent（agentscope）主 agent + rewrite_agent 子 agent 委派，工具通过 Toolkit 注册
- **向量存储**: MeetingVector 用 `Long meetingId` 裸外键（非 @ManyToOne），因 Hibernate 无法映射 pgvector VECTOR 列，读取走 JdbcTemplate
- **SSE 流式**: ChatStreamService 使用虚拟线程 + AtomicBoolean 实现前后端生命周期解耦
- **检索管线**: QueryPlan → 全文+向量并行 → RRF 融合 → Rerank → 时间衰减 → 去重 → 证据评估

### Core Business Flows

1. **文件上传转录**: FileProcessingService 保存 → TranscriptionService 异步 ASR → `.transcription.md` 侧车文件
2. **知识库上传**: KnowledgeBaseService → 提取内容 → VectorizationService 分块向量化
3. **RAG 搜索**: HybridSearchService 混合检索（全文 + 向量 + RRF）
4. **对话**: ChatController → ChatStreamService → AgentScope SSE 流式
5. **改写**: RewriteService → DeepSeek 流式改写 + 校对（人名/术语/格式）

## Database

- PostgreSQL + pgvector 扩展
- `meeting_minutes` 表：会议元数据 + 转写内容
- `meeting_vectors` 表：分块向量（cosine distance 索引）
- `dialogue_sessions` / `dialogue_messages` 表：对话会话 + 消息
- JPA `ddl-auto: update`（开发环境自动建表）

## Must Follow

- 完成某一项需要更新 `docs/superpowers/开发计划.md`
- 开发计划没有的任务需要进行添加

## Related Docs

- [设计文档](docs/superpowers/specs/2026-06-19-meeting-agent-design.md)
- --dangerously-skip-permissions
