package com.meeting.retrieval.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.retrieval.model.ChunkMetadataParser;
import com.meeting.retrieval.model.ChunkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FullTextSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 全文检索：对 content_tsv（tsvector 列）执行 PostgreSQL 原生全文搜索。
     * <p>
     * 输入文本 → plainto_tsquery 分词（中文）→ AND 改 OR 放宽匹配 →
     * @@ 匹配 tsvector → ts_rank 排序 → LIMIT
     */
    public List<ChunkResult> search(String query, int topK) {
        String sql = """
            SELECT c.id, c.document_id, c.content, c.chunk_index, c.speaker, c.metadata,
                   ts_rank(c.content_tsv, to_tsquery('chinese',
                       replace(plainto_tsquery('chinese', ?)::text, ' & ', ' | ')
                   )) AS score
            FROM document_chunk_v2 c
            WHERE c.content_tsv @@ to_tsquery('chinese',
                replace(plainto_tsquery('chinese', ?)::text, ' & ', ' | ')
            )
            ORDER BY score DESC LIMIT ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            var parsed = ChunkMetadataParser.parse(rs.getString("metadata"), objectMapper);
            return ChunkResult.builder()
                    .chunkId(rs.getLong("id"))
                    .documentId(rs.getLong("document_id"))
                    .content(rs.getString("content"))
                    .chunkIndex(rs.getInt("chunk_index"))
                    .speaker(rs.getString("speaker"))
                    .fileName(parsed.fileName())
                    .meetingDate(parsed.meetingDate())
                    .participants(parsed.participants())
                    .topic(parsed.topic())
                    .sectionHeading(parsed.sectionHeading())
                    .ftsScore(rs.getDouble("score"))
                    .ftsRank(rowNum + 1)
                    .build();
        }, query, query, topK);
    }
}