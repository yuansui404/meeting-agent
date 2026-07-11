package com.meeting.document.model;

public record VectorSearchHit(
        Long id,
        Long documentId,
        String content,
        Integer chunkIndex,
        String speaker,
        Double similarityScore
) {}