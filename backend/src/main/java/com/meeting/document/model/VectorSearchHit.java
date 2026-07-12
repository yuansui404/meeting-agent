package com.meeting.document.model;

public record VectorSearchHit(
        Long id,
        Long documentId,
        String content,
        Integer chunkIndex,
        String speaker,
        String metadata, // 分块metadata JSON：含document_title、meeting_date等
        Double similarityScore // [-1，1]
) {}