package com.meeting.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
public class ChunkResult {
    private Long chunkId;
    private Long documentId;
    private String content;
    private int chunkIndex;
    private String speaker;
    private String fileName;
    private double vectorScore;
    private double ftsScore;
    private double rrfScore;
    private double finalScore;
    private Integer vectorRank;
    private Integer ftsRank;
    private LocalDate meetingDate; // 会议日期（从分块metadata解析，用于时间衰减）
}
