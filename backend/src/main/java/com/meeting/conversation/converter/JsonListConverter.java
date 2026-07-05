package com.meeting.conversation.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.FileMetadata;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Converter(autoApply = false)
public class JsonListConverter implements AttributeConverter<List<FileMetadata>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<FileMetadata>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<FileMetadata> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON list: {}", e.getMessage());
            return "[]";
        }
    }

    @Override
    public List<FileMetadata> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSON list: {}", e.getMessage());
            return null;
        }
    }
}
