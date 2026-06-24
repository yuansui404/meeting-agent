package com.meeting.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ChunkSegment {
    private String content;
    private int index;
    private String speaker;
    private String sectionType;
}
