package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.QueryRewriter;
import com.meeting.service.VectorizationService;
import com.meeting.service.VectorizationService.ScoredVector;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKnowledgeBaseTool implements AgentTool {

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;
    private final QueryRewriter queryRewriter;
    private final JdbcTemplate jdbcTemplate;

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

        int topK = 10;
        Object topKObj = input.get("topK");
        if (topKObj instanceof Number n) {
            topK = Math.max(1, Math.min(20, n.intValue()));
        }

        try {
            // 1. Vector search with original query (semantic matching)
            List<ScoredVector> vectorResults = vectorizationService.searchSimilarWithScores(query, topK);

            // 2. Extract keywords and do precise matching
            List<String> keywords = queryRewriter.extractKeywords(query);

            // 3. Batch-load all meeting metadata to avoid N+1 queries
            Set<Long> allMeetingIds = new HashSet<>();
            for (ScoredVector v : vectorResults) allMeetingIds.add(v.meetingId());
            for (String keyword : keywords) {
                for (MeetingMinutes mm : meetingRepository.searchByParticipants(keyword)) {
                    allMeetingIds.add(mm.getId());
                }
            }
            Map<Long, MeetingMinutes> meetingMap = meetingRepository.findAllById(allMeetingIds).stream()
                    .collect(Collectors.toMap(MeetingMinutes::getId, m -> m));

            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

            // 4. Build result items — keep all vector chunks
            Set<Long> meetingsInResults = new HashSet<>();
            List<Map<String, Object>> items = new ArrayList<>();

            for (ScoredVector v : vectorResults) {
                Long mid = v.meetingId();
                meetingsInResults.add(mid);
                MeetingMinutes mm = meetingMap.get(mid);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", mm != null && mm.getTitle() != null ? mm.getTitle() : "未知文件");
                item.put("content", v.content() != null ? v.content() : "");
                item.put("similarity", Math.round(v.similarity() * 100.0) / 100.0);
                item.put("date", mm != null && mm.getMeetingDate() != null ? mm.getMeetingDate().format(fmt) : "");
                items.add(item);
            }

            // 5. Add keyword-based results — only for meetings NOT already covered
            for (String keyword : keywords) {
                List<MeetingMinutes> byParticipants = meetingRepository.searchByParticipants(keyword);
                for (MeetingMinutes mm : byParticipants) {
                    if (!meetingsInResults.contains(mm.getId())) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", mm.getTitle() != null ? mm.getTitle() : "");
                        item.put("content", "与会人: " + (mm.getParticipants() != null ? mm.getParticipants() : "未知"));
                        item.put("similarity", 0.95);
                        item.put("date", mm.getMeetingDate() != null ? mm.getMeetingDate().format(fmt) : "");
                        items.add(item);
                        meetingsInResults.add(mm.getId());
                    }
                }

                List<Long> contentMatchIds = searchVectorContent(keyword, topK);
                for (Long mid : contentMatchIds) {
                    if (!meetingsInResults.contains(mid)) {
                        MeetingMinutes mm = meetingMap.get(mid);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", mm != null && mm.getTitle() != null ? mm.getTitle() : "未知文件");
                        item.put("content", "");
                        item.put("similarity", 0.5);
                        item.put("date", mm != null && mm.getMeetingDate() != null ? mm.getMeetingDate().format(fmt) : "");
                        items.add(item);
                        meetingsInResults.add(mid);
                    }
                }
            }

            if (items.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到相关结果。"));
            }

            // Sort by similarity descending, limit to topK
            items.sort((a, b) -> Double.compare(toDouble(b.get("similarity")), toDouble(a.get("similarity"))));
            if (items.size() > topK) {
                items = items.subList(0, topK);
            }

            return Mono.just(ToolResultBlock.text(JsonUtil.toJsonArray(items)));
        } catch (Exception e) {
            log.warn("Knowledge base search failed", e);
            return Mono.just(ToolResultBlock.error("搜索知识库异常，请稍后重试"));
        }
    }

    private List<Long> searchVectorContent(String keyword, int limit) {
        try {
            String sql = "SELECT DISTINCT meeting_id FROM meeting_vectors WHERE content ILIKE ? LIMIT ?";
            String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pattern, limit);
            return rows.stream()
                    .map(r -> ((Number) r.get("meeting_id")).longValue())
                    .toList();
        } catch (Exception e) {
            log.warn("Vector content ILIKE search failed for '{}': {}", keyword, e.getMessage());
            return List.of();
        }
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
