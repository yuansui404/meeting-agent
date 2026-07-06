package com.meeting.conversation.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.conversation.model.AgentResponseMetadata;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Converter(autoApply = false)
public class JsonMetadataConverter implements AttributeConverter<AgentResponseMetadata, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(AgentResponseMetadata attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize AgentResponseMetadata: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public AgentResponseMetadata convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, AgentResponseMetadata.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize AgentResponseMetadata: {}", e.getMessage());
            return null;
        }
    }
}
