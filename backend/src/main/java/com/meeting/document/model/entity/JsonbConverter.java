package com.meeting.document.model.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts String <-> JSONB using Hibernate 6+ native JSON support.
 * The `columnDefinition = "jsonb"` in @Column tells Hibernate to use JSONB type.
 * This converter just ensures null-safe handling.
 */
@Converter(autoApply = true)
public class JsonbConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute != null ? attribute : "{}";
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData != null ? dbData : "{}";
    }
}
