package com.meeting.document.model;

/**
 * 向量搜索的查询结果映射。
 * 由 @SqlResultSetMapping 从原生 SQL 查询结果按列位置构造。
 */
public record VectorSearchHit(
        Long id,                    // document_chunk_v2.id
        Long documentId,            // document_chunk_v2.document_id
        String content,             // 分块文本
        Integer chunkIndex,         // 在文档中的第几个分块（从 0 开始）
        String speaker,             // 说话人，仅会议转写类文档有值
        String metadata,            // 分块 metadata JSON: document_title, meeting_date, participants, topic, section_heading
        Double similarityScore      // 向量相似度，范围 [-1, 1]，1 表示语义完全一致，-1 表示相反
) {}