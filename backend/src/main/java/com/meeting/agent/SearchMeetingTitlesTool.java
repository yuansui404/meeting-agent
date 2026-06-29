package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class SearchMeetingTitlesTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchMeetingTitlesTool.class);

    private final MeetingMinutesRepository meetingRepository;

    public SearchMeetingTitlesTool(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "search_meeting_titles";
    }

    @Override
    public String getDescription() {
        return "通过标题关键词搜索会议。当用户知道会议的大概名称或想找某次特定会议时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "会议标题关键词"
        ));
        properties.put("limit", Map.of(
                "type", "number",
                "description", "返回结果数，默认10，最多50"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("keyword")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String keyword = input.getOrDefault("keyword", "").toString();
        if (keyword.isBlank()) {
            return Mono.just(ToolResultBlock.text("搜索关键词不能为空"));
        }

        int limit = 10;
        if (input.get("limit") instanceof Number n) {
            limit = Math.max(1, Math.min(50, n.intValue()));
        }

        try {
            List<MeetingMinutes> meetings = meetingRepository.searchByTitleKeyword(keyword, limit);
            if (meetings.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到标题包含「" + keyword + "」的会议。"));
            }

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

            return Mono.just(ToolResultBlock.text(toJson(items)));
        } catch (Exception e) {
            log.warn("Search meeting titles failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("搜索会议异常，请稍后重试"));
        }
    }

    private String toJson(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            Map<String, Object> item = items.get(i);
            int j = 0;
            for (var entry : item.entrySet()) {
                if (j++ > 0) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object val = entry.getValue();
                if (val instanceof String s) {
                    sb.append("\"").append(escapeJson(s)).append("\"");
                } else {
                    sb.append(val);
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
