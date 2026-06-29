package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.VectorizationService;
import com.meeting.service.VectorizationService.ScoredVector;
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
public class SearchKnowledgeBaseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeBaseTool.class);

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;

    public SearchKnowledgeBaseTool(VectorizationService vectorizationService,
                                   MeetingMinutesRepository meetingRepository) {
        this.vectorizationService = vectorizationService;
        this.meetingRepository = meetingRepository;
    }

    @Override
    public String getName() {
        return "search_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "搜索知识库中的会议记录内容。当用户询问会议具体内容、决定、讨论要点等需要查阅历史会议信息时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词，尽量简洁准确"
        ));
        properties.put("topK", Map.of(
                "type", "number",
                "description", "返回结果数，默认5，最少1，最多20"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String query = input.getOrDefault("query", "").toString();
        if (query.isBlank()) {
            return Mono.just(ToolResultBlock.text("搜索关键词不能为空"));
        }

        int topK = 5;
        Object topKObj = input.get("topK");
        if (topKObj instanceof Number n) {
            topK = Math.max(1, Math.min(20, n.intValue()));
        }

        try {
            List<ScoredVector> results = vectorizationService.searchSimilarWithScores(query, topK);
            if (results.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到相关结果。"));
            }

            Map<Long, String> titleCache = new HashMap<>();
            Map<Long, String> dateCache = new HashMap<>();
            for (ScoredVector v : results) {
                titleCache.computeIfAbsent(v.meetingId(),
                        id -> meetingRepository.findById(id)
                                .map(MeetingMinutes::getTitle)
                                .orElse("未知文件"));
                dateCache.computeIfAbsent(v.meetingId(),
                        id -> meetingRepository.findById(id)
                                .map(m -> m.getMeetingDate() != null
                                        ? m.getMeetingDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        : "")
                                .orElse(""));
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (ScoredVector v : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", titleCache.getOrDefault(v.meetingId(), ""));
                item.put("content", v.content());
                item.put("similarity", Math.round(v.similarity() * 100.0) / 100.0);
                item.put("date", dateCache.getOrDefault(v.meetingId(), ""));
                items.add(item);
            }

            return Mono.just(ToolResultBlock.text(toJson(items)));
        } catch (Exception e) {
            log.warn("Knowledge base search failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("搜索知识库异常，请稍后重试"));
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
