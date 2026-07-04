package com.meeting.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class JsonUtil {

    private JsonUtil() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON serialization failed", e);
        }
    }

    public static String toJsonArray(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON serialization failed", e);
        }
    }
}
