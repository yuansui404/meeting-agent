package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMeetingTitlesTool implements AgentTool {

    private final DocumentRepository documentRepository;

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
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String keyword = input.getOrDefault("keyword", "").toString();
            if (keyword.isBlank()) {
                return ToolResultBlock.text("搜索关键词不能为空");
            }

            int limit = 10;
            if (input.get("limit") instanceof Number n) {
                limit = Math.max(1, Math.min(50, n.intValue()));
            }

            List<DocumentEntity> docs = documentRepository.searchByTitleKeyword(keyword, limit);
            if (docs.isEmpty()) {
                return ToolResultBlock.text("未找到标题包含「" + keyword + "」的会议。");
            }

            List<Map<String, Object>> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
            for (DocumentEntity d : docs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", d.getId());
                item.put("title", d.getTitle() != null ? d.getTitle() : "");
                item.put("date", d.getMeetingDate() != null ? d.getMeetingDate().format(fmt) : "");
                item.put("status", d.getStatus());
                items.add(item);
            }

            return ToolResultBlock.text(JsonUtil.toJsonArray(items));
        });
    }

}
