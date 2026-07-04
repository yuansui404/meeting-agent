package com.meeting.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonUtil {

    private JsonUtil() {}

    public static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(toJsonValue(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    public static String toJsonArray(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJson(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static String toJsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJsonValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (val instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : (Set<Map.Entry<?, ?>>) m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":");
                sb.append(toJsonValue(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
