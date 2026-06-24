package com.meeting.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class Citation {
    private int sourceId;
    private String fileName;
    private String content;
    private Integer chunkIndex;
}
