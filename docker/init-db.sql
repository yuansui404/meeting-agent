-- ============================================================
-- Agent 会话表
-- 存储 AgentState 全量快照 (TEXT)，由 PgAgentStateStore 自动管理
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(500) NOT NULL DEFAULT '新对话',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    state_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_sessions_status ON agent_sessions(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_session_id ON agent_sessions(session_id);

-- ============================================================
-- 对话消息存储表
-- ============================================================
CREATE TABLE IF NOT EXISTS dialogue_messages (
    id BIGSERIAL PRIMARY KEY,
    dialogue_id BIGINT NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL DEFAULT 'user',
    content TEXT NOT NULL DEFAULT '',
    files TEXT,
    message_type VARCHAR(32) DEFAULT 'text',
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_dialogue_messages_dialogue_id ON dialogue_messages(dialogue_id, id);

-- ============================================================
-- RAG 文档表
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    meeting_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    chunk_count INT NOT NULL DEFAULT 0,
    transcription TEXT,
    style_exemplar BOOLEAN DEFAULT FALSE,
    style_tags VARCHAR(500),
    participants TEXT,
    duration INTEGER,
    md_file_path VARCHAR(500),
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
    metadata TEXT DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 索引
-- ============================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 文档表索引
CREATE INDEX IF NOT EXISTS idx_document_status ON document(status);
CREATE INDEX IF NOT EXISTS idx_document_meeting_date ON document(meeting_date);

-- 文档块表索引
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

-- 全文搜索支持（中文分词）
CREATE EXTENSION IF NOT EXISTS zhparser;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese') THEN
        CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l,m WITH simple;
        ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR en WITH simple;
    END IF;
END $$;

SET zhparser.punctuation_ignore = 't';
SET zhparser.seg_with_duality = 'f';
SET zhparser.dict_in_memory = 't';
SET zhparser.multi_short = 't';

ALTER DATABASE meeting_agent SET zhparser.punctuation_ignore = 't';
ALTER DATABASE meeting_agent SET zhparser.seg_with_duality = 'f';
ALTER DATABASE meeting_agent SET zhparser.dict_in_memory = 't';
ALTER DATABASE meeting_agent SET zhparser.multi_short = 't';

DROP INDEX IF EXISTS idx_chunk_content_tsv;
ALTER TABLE document_chunk DROP COLUMN IF EXISTS content_tsv;
ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('chinese', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);

-- ============================================================
-- Profile 元数据表
-- ============================================================
CREATE TABLE IF NOT EXISTS profile_metadata (
    id BIGSERIAL PRIMARY KEY,
    filename VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
