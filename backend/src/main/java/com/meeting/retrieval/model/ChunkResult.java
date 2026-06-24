package com.meeting.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ChunkResult {
    private Long chunkId;
    private Long documentId;
    private String content;
    private int chunkIndex;
    private String speaker;
    private String sectionType;
    private String fileName;
    private double vectorScore;
    private double ftsScore;
    private double rrfScore;
    private double finalScore;
    private int vectorRank;
    private int ftsRank;
}
