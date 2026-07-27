package com.meeting.agent;

import com.meeting.retrieval.model.Citation;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * search_documents 工具的类型安全返回值。
 * 替代原有的 Map<String, Object> 拼装方式。
 */
@Value
@Builder
@JsonSerialize
public class SearchToolResponse {

    String evidenceLevel;
    String strategyUsed;
    int totalCandidates;
    List<ChunkItem> results;
    List<Citation> citations;
    String queryUsed;

    @Value
    @Builder
    @JsonSerialize
    public static class ChunkItem {
        String source;
        String content;
        String speaker;
        String participants;
        String topic;
        String sectionHeading;
    }
}