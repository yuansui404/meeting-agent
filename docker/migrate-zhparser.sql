-- ============================================================
-- 迁移脚本：添加 zhparser 中文分词全文搜索支持
-- 用法：docker exec -i docker-postgres-1 psql -U user -d meeting_agent < docker/migrate-zhparser.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS zhparser;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese') THEN
        CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l,m WITH simple;
        ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR en WITH simple;
    END IF;
END $$;

ALTER DATABASE meeting_agent SET zhparser.punctuation_ignore = 't';
ALTER DATABASE meeting_agent SET zhparser.seg_with_duality = 'f';
ALTER DATABASE meeting_agent SET zhparser.dict_in_memory = 't';
ALTER DATABASE meeting_agent SET zhparser.multi_short = 't';

SET zhparser.punctuation_ignore = 't';
SET zhparser.seg_with_duality = 'f';
SET zhparser.dict_in_memory = 't';
SET zhparser.multi_short = 't';

DROP INDEX IF EXISTS idx_chunk_content_tsv;
ALTER TABLE document_chunk DROP COLUMN IF EXISTS content_tsv;
ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('chinese', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);

-- 验证
SELECT 'Migration complete.' AS status;
SELECT count(*) AS total_chunks FROM document_chunk;
SELECT count(*) AS chunks_with_null_tsv FROM document_chunk WHERE content_tsv IS NULL;