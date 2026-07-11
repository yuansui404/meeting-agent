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
    private String topic;           // h3 议题标题，如 "### 关于工业互联网集成项目..."
    private String sectionHeading;  // h2 章节标题，如 "## 会议讨论" / "## 会议决策"
    private String documentTitle;   // 文档标题
}
