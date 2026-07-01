-- ============================================================
-- Agent 会话表（替代旧 dialogues + dialogue_messages）
-- 存储 AgentState 全量快照 (TEXT)，由 PgAgentStateStore 自动管理
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(500) NOT NULL DEFAULT '新对话',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    imported BOOLEAN NOT NULL DEFAULT FALSE,
    message_count INT NOT NULL DEFAULT 0,
    context_summary TEXT,
    state_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 会议纪要表（单表存储所有会议信息）
CREATE TABLE IF NOT EXISTS meeting_minutes (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT,
    duration INTEGER,
    transcription TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'processing',
    knowledge_base BOOLEAN DEFAULT FALSE,
    dialogue_id BIGINT REFERENCES agent_sessions(id) ON DELETE SET NULL,
    md_file_path VARCHAR(500),
    meeting_date TIMESTAMP
);

-- 会议向量表（需要 pgvector 扩展）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS meeting_vectors (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT REFERENCES meeting_minutes(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding VECTOR(1536),
    chunk_index INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 索引
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_meeting_minutes_search ON meeting_minutes USING gin(to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '')));
-- 分别创建 trigram 索引（gin_trgm_ops 不支持表达式）
CREATE INDEX IF NOT EXISTS idx_meeting_minutes_title_trgm ON meeting_minutes USING gin(title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_meeting_minutes_trans_trgm ON meeting_minutes USING gin(transcription gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_meeting_vectors_embedding ON meeting_vectors USING ivfflat(embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_status ON agent_sessions(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_session_id ON agent_sessions(session_id);

-- ============================================================
-- RAG 新管线表（追加在旧表定义之后）
-- 用于新的 RAG 文档管理、知识库检索、Agent 问答功能
-- ============================================================

-- 文档表
CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    meeting_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    chunk_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 文档块表（含向量）
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding VECTOR(1024),
    chunk_index INT NOT NULL,
    speaker VARCHAR(100),
    section_type VARCHAR(50),
    metadata TEXT DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 会话表
CREATE TABLE IF NOT EXISTS conversation (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL DEFAULT '新对话',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    context_summary TEXT,
    compression_history TEXT DEFAULT '[]',
    message_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 消息表
CREATE TABLE IF NOT EXISTS message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    trace_id VARCHAR(64),
    metadata TEXT DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 文档表索引
CREATE INDEX IF NOT EXISTS idx_document_status ON document(status);
CREATE INDEX IF NOT EXISTS idx_document_meeting_date ON document(meeting_date);

-- 文档块表索引
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_section_type ON document_chunk(section_type);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

-- 全文搜索支持（需先删除已存在的 tsvector 列再重新添加，幂等处理）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'document_chunk' AND column_name = 'content_tsv'
    ) THEN
        ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector
            GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);

-- 会话表索引
CREATE INDEX IF NOT EXISTS idx_conversation_updated_at ON conversation(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_status ON conversation(status);

-- 消息表索引
CREATE INDEX IF NOT EXISTS idx_message_conversation_id ON message(conversation_id);
CREATE INDEX IF NOT EXISTS idx_message_created_at ON message(created_at);

-- ============================================================
-- 改写风格学习表（2026-06-24 追加）
-- ============================================================

-- meeting_minutes: 风格范例标记
ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS style_exemplar BOOLEAN DEFAULT FALSE;
ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS style_tags VARCHAR(500);

-- meeting_vectors: 优先级分数（用于风格检索加权排序）
ALTER TABLE meeting_vectors ADD COLUMN IF NOT EXISTS priority_score DOUBLE PRECISION DEFAULT 0.0;
CREATE INDEX IF NOT EXISTS idx_meeting_vectors_priority ON meeting_vectors(priority_score);

-- 改写结果表（每次改写一条记录，支持多文件源 + 多版本）
CREATE TABLE IF NOT EXISTS rewrite_result (
    id BIGSERIAL PRIMARY KEY,
    dialogue_id BIGINT NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    source_file_ids TEXT NOT NULL,
    reference_ids TEXT,
    content TEXT NOT NULL,
    docx_path VARCHAR(500),
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rewrite_result_dialogue ON rewrite_result(dialogue_id);

-- 段落反馈表
CREATE TABLE IF NOT EXISTS rewrite_feedback (
    id BIGSERIAL PRIMARY KEY,
    rewrite_result_id BIGINT NOT NULL REFERENCES rewrite_result(id) ON DELETE CASCADE,
    paragraph_index INT NOT NULL,
    action VARCHAR(10) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rewrite_feedback_result ON rewrite_feedback(rewrite_result_id);

-- ============================================================
-- 数据迁移（从旧 dialogues/dialogue_messages 表迁移到 agent_sessions）
-- 幂等执行：仅当旧 dialogues 表存在且 agent_sessions 暂无数据时执行
-- ============================================================
DROP TABLE IF EXISTS dialogue_messages, dialogues CASCADE;
-- 注：旧表的 CASCADE DROP 会同时清理旧 FK 约束。如果旧表不存在则跳过。

-- 后续所有的 FK 已在上文直接引用 agent_sessions。
