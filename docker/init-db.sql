-- 会议纪要表
CREATE TABLE IF NOT EXISTS meeting_minutes (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT,
    duration INTEGER,
    transcription TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'processing'
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

-- 对话表
CREATE TABLE IF NOT EXISTS dialogues (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active',
    user_id VARCHAR(255),
    imported BOOLEAN DEFAULT FALSE,
    meeting_id BIGINT REFERENCES meeting_minutes(id) ON DELETE SET NULL
);

-- 对话消息表
CREATE TABLE IF NOT EXISTS dialogue_messages (
    id BIGSERIAL PRIMARY KEY,
    dialogue_id BIGINT REFERENCES dialogues(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    message_type VARCHAR(50) DEFAULT 'text',
    meeting_context_id BIGINT REFERENCES meeting_minutes(id) ON DELETE CASCADE
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_meeting_minutes_search ON meeting_minutes USING gin(to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(transcription, '')));
CREATE INDEX IF NOT EXISTS idx_meeting_vectors_embedding ON meeting_vectors USING ivfflat(embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_dialogues_status ON dialogues(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_dialogue_messages_timestamp ON dialogue_messages(dialogue_id, timestamp);
