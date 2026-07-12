package com.meeting.retrieval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

@Slf4j
public final class ChunkMetadataParser {

    private ChunkMetadataParser() {}

    public static ParsedMetadata parse(String metadataJson, ObjectMapper objectMapper) {
        if (metadataJson == null) return new ParsedMetadata("", null);
        try {
            JsonNode meta = objectMapper.readTree(metadataJson);
            String fileName = meta.has("document_title") ? meta.get("document_title").asText() : "";
            LocalDate meetingDate = meta.has("meeting_date") ? LocalDate.parse(meta.get("meeting_date").asText()) : null;
            return new ParsedMetadata(fileName, meetingDate);
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata: {}", e.getMessage());
            return new ParsedMetadata("", null);
        }
    }

    public record ParsedMetadata(String fileName, LocalDate meetingDate) {}
}