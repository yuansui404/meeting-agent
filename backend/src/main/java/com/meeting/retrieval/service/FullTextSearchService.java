package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FullTextSearchService {

    private final JdbcTemplate jdbcTemplate;

    public List<ChunkResult> search(String query, int topK) {
        String sql = """
            SELECT c.id, c.document_id, c.content, c.chunk_index, c.speaker, c.section_type,
                   d.title AS file_name,
                   ts_rank(c.content_tsv, plainto_tsquery('simple', ?)) AS score
            FROM document_chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.content_tsv @@ plainto_tsquery('simple', ?)
            ORDER BY score DESC
            LIMIT ?
            """;

        List<ChunkResult> results = jdbcTemplate.query(sql,
                ps -> {
                    ps.setString(1, query);
                    ps.setString(2, query);
                    ps.setInt(3, topK);
                },
                (rs, rowNum) -> ChunkResult.builder()
                        .chunkId(rs.getLong("id"))
                        .documentId(rs.getLong("document_id"))
                        .content(rs.getString("content"))
                        .chunkIndex(rs.getInt("chunk_index"))
                        .speaker(rs.getString("speaker"))
                        .sectionType(rs.getString("section_type"))
                        .fileName(rs.getString("file_name"))
                        .ftsScore(rs.getDouble("score"))
                        .ftsRank(rowNum + 1)
                        .build()
        );

        log.debug("Full-text search: query={}, topK={}, found={}", query, topK, results.size());
        return results;
    }
}
