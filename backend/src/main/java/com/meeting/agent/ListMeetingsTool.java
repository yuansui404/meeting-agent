package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class ListMeetingsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListMeetingsTool.class);

    private final MeetingMinutesRepository meetingRepository;

    public ListMeetingsTool(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "list_meetings";
    }

    @Override
    public String getDescription() {
        return "浏览会议记录列表。当用户想知道最近有哪些会议、查看会议概览时使用。按创建时间倒序排列。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("page", Map.of(
                "type", "number",
                "description", "页码，从0开始（默认0）"
        ));
        properties.put("size", Map.of(
                "type", "number",
                "description", "每页条数（默认10，最多50）"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();

        int page = 0;
        if (input.get("page") instanceof Number n) {
            page = Math.max(0, n.intValue());
        }
        int size = 10;
        if (input.get("size") instanceof Number n) {
            size = Math.max(1, Math.min(50, n.intValue()));
        }

        try {
            Page<MeetingMinutes> meetingPage = meetingRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
            List<MeetingMinutes> meetings = meetingPage.getContent();

            List<Map<String, Object>> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            for (MeetingMinutes m : meetings) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.getId());
                item.put("title", m.getTitle());
                item.put("date", m.getMeetingDate() != null ? m.getMeetingDate().format(fmt) : "");
                item.put("status", m.getStatus());
                items.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", meetingPage.getTotalElements());
            result.put("page", meetingPage.getNumber());
            result.put("items", items);

            return Mono.just(ToolResultBlock.text(toJson(result)));
        } catch (Exception e) {
            log.warn("List meetings failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("获取会议列表异常，请稍后重试"));
        }
    }

    private String toJson(Map<String, Object> map) {
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

    @SuppressWarnings("unchecked")
    private String toJsonValue(Object val) {
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
            return toJson((Map<String, Object>) m);
        }
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
