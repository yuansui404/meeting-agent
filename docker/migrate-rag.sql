-- RAG 管线统一数据迁移脚本
-- 从 meeting_minutes + meeting_vectors 迁移到 document + document_chunk
-- 执行前请确认 document 和 document_chunk 表已通过 JPA 自动创建

BEGIN;

-- 1. 从 meeting_minutes 迁移到 document
INSERT INTO document (title, file_type, file_path, file_size, meeting_date, status, chunk_count,
    transcription, style_exemplar, style_tags, participants, duration, md_file_path,
    created_at, updated_at)
SELECT
    mm.title,
    CASE
        WHEN mm.file_path LIKE '%.pdf' THEN 'pdf'
        WHEN mm.file_path LIKE '%.docx' THEN 'docx'
        WHEN mm.file_path LIKE '%.doc' THEN 'doc'
        WHEN mm.file_path LIKE '%.txt' THEN 'txt'
        WHEN mm.file_path LIKE '%.md' THEN 'md'
        ELSE 'unknown'
    END,
    mm.file_path,
    mm.file_size,
    mm.meeting_date::date,  -- LocalDateTime → LocalDate
    COALESCE(mm.status, 'COMPLETED'),
    0,  -- chunk_count will be updated in step 3
    mm.transcription,
    COALESCE(mm.style_exemplar, false),
    mm.style_tags,
    mm.participants,
    mm.duration,
    mm.md_file_path,
    mm.created_at,
    mm.updated_at
FROM meeting_minutes mm
WHERE NOT EXISTS (
    SELECT 1 FROM document d WHERE d.file_path = mm.file_path
);

-- 2. 从 meeting_vectors 迁移到 document_chunk
-- 通过 file_path 关联 meeting_minutes → document
INSERT INTO document_chunk (document_id, content, embedding, chunk_index, speaker, section_type, metadata, created_at)
SELECT
    d.id,
    mv.content,
    mv.embedding,
    mv.chunk_index,
    NULL,  -- 旧数据无 speaker
    NULL,  -- 旧数据无 section_type
    CASE WHEN mv.priority_score > 0
        THEN jsonb_build_object('priorityScore', mv.priority_score)::text
        ELSE NULL
    END,
    mv.created_at
FROM meeting_vectors mv
JOIN meeting_minutes mm ON mv.meeting_id = mm.id
JOIN document d ON d.file_path = mm.file_path
WHERE mv.chunk_index >= 0;  -- 排除 chunk_index=-1 的 participants 伪 chunk

-- 3. 更新 document.chunk_count
UPDATE document d
SET chunk_count = (
    SELECT COUNT(*) FROM document_chunk dc WHERE dc.document_id = d.id
)
WHERE d.chunk_count = 0;

COMMIT;
