package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListMeetingsTool implements AgentTool {

    private final MeetingMinutesRepository meetingRepository;

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
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();

            int page = 0;
            if (input.get("page") instanceof Number n) {
                page = Math.max(0, n.intValue());
            }
            int size = 10;
            if (input.get("size") instanceof Number n) {
                size = Math.max(1, Math.min(50, n.intValue()));
            }

            Page<MeetingMinutes> meetingPage = meetingRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
            List<MeetingMinutes> meetings = meetingPage.getContent();

            List<Map<String, Object>> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            for (MeetingMinutes m : meetings) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.getId());
                item.put("title", m.getTitle() != null ? m.getTitle() : "");
                item.put("date", m.getMeetingDate() != null ? m.getMeetingDate().format(fmt) : "");
                item.put("status", m.getStatus());
                items.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", meetingPage.getTotalElements());
            result.put("page", meetingPage.getNumber());
            result.put("items", items);

            return ToolResultBlock.text(JsonUtil.toJson(result));
        });
    }

}
