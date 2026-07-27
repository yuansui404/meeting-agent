package com.meeting.retrieval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

/**
 * 解析分块 metadata JSON，提取结构化字段。
 * 所有字段都有默认值，解析失败不抛异常，保证搜索链路不因 metadata 格式问题中断。
 */
@Slf4j
public final class ChunkMetadataParser {

    private ChunkMetadataParser() {}

    /**
     * @param metadataJson document_chunk_v2.metadata 字段的 JSON 字符串
     * @return ParsedMetadata，缺失字段用空字符串/null 填充
     */
    public static ParsedMetadata parse(String metadataJson, ObjectMapper objectMapper) {
        if (metadataJson == null) return new ParsedMetadata("", null, "", "", "");
        try {
            JsonNode meta = objectMapper.readTree(metadataJson);
            String fileName = meta.has("document_title") ? meta.get("document_title").asText() : "";
            LocalDate meetingDate = meta.has("meeting_date") ? LocalDate.parse(meta.get("meeting_date").asText()) : null;
            String participants = meta.has("participants") ? meta.get("participants").asText() : "";
            String topic = meta.has("topic") ? meta.get("topic").asText() : "";
            String sectionHeading = meta.has("section_heading") ? meta.get("section_heading").asText() : "";
            return new ParsedMetadata(fileName, meetingDate, participants, topic, sectionHeading);
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata: {}", e.getMessage());
            return new ParsedMetadata("", null, "", "", "");
        }
    }

    public record ParsedMetadata(String fileName, LocalDate meetingDate,
                                 String participants, String topic, String sectionHeading) {}
}