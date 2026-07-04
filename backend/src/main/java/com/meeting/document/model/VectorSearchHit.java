package com.meeting.document.model;

public record VectorSearchHit(
        Long id,
        Long documentId,
        String content,
        Integer chunkIndex,
        String speaker,
        String sectionType,
        Double similarityScore
) {}
