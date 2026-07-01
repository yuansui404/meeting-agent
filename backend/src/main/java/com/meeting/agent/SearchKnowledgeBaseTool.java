package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.QueryRewriter;
import com.meeting.service.VectorizationService;
import com.meeting.service.VectorizationService.ScoredVector;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SearchKnowledgeBaseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeBaseTool.class);

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;
    private final QueryRewriter queryRewriter;
    private final JdbcTemplate jdbcTemplate;

    public SearchKnowledgeBaseTool(VectorizationService vectorizationService,
                                    MeetingMinutesRepository meetingRepository,
                                    QueryRewriter queryRewriter,
                                    JdbcTemplate jdbcTemplate) {
        this.vectorizationService = vectorizationService;
        this.meetingRepository = meetingRepository;
        this.queryRewriter = queryRewriter;
        this.jdbcTemplate = jdbcTemplate;
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

            // 3. Build result items — keep all vector chunks (not deduplicated by meeting_id)
            //    so the agent sees multiple relevant chunks from the same meeting
            Map<Long, String> titleCache = new HashMap<>();
            Map<Long, String> dateCache = new HashMap<>();
            Set<Long> meetingsInResults = new HashSet<>();

            List<Map<String, Object>> items = new ArrayList<>();

            for (ScoredVector v : vectorResults) {
                Long mid = v.meetingId();
                titleCache.computeIfAbsent(mid, id -> meetingRepository.findById(id)
                        .map(MeetingMinutes::getTitle).orElse("未知文件"));
                dateCache.computeIfAbsent(mid, id -> meetingRepository.findById(id)
                        .map(m -> m.getMeetingDate() != null
                                ? m.getMeetingDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                : "")
                        .orElse(""));
                meetingsInResults.add(mid);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", titleCache.get(mid));
                item.put("content", v.content());
                item.put("similarity", Math.round(v.similarity() * 100.0) / 100.0);
                item.put("date", dateCache.get(mid));
                items.add(item);
            }

            // Then, add keyword-based results — only for meetings NOT already covered
            // by vector search, so we don't override chunk content with empty strings.
            for (String keyword : keywords) {
                // Search participants column
                List<MeetingMinutes> byParticipants = meetingRepository.searchByParticipants(keyword);
                for (MeetingMinutes mm : byParticipants) {
                    if (!meetingsInResults.contains(mm.getId())) {
                        titleCache.computeIfAbsent(mm.getId(), id -> mm.getTitle());
                        dateCache.computeIfAbsent(mm.getId(), id -> mm.getMeetingDate() != null
                                ? mm.getMeetingDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                : "");

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", mm.getTitle());
                        item.put("content", "与会人: " + mm.getParticipants());
                        item.put("similarity", 0.95);
                        item.put("date", dateCache.get(mm.getId()));
                        items.add(item);
                        meetingsInResults.add(mm.getId());
                    }
                }

                // Search vector content via ILIKE
                List<Long> contentMatchIds = searchVectorContent(keyword, topK);
                for (Long mid : contentMatchIds) {
                    if (!meetingsInResults.contains(mid)) {
                        titleCache.computeIfAbsent(mid, id -> meetingRepository.findById(id)
                                .map(MeetingMinutes::getTitle).orElse("未知文件"));
                        dateCache.computeIfAbsent(mid, id -> meetingRepository.findById(id)
                                .map(m -> m.getMeetingDate() != null
                                        ? m.getMeetingDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        : "")
                                .orElse(""));

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", titleCache.get(mid));
                        item.put("content", "");
                        item.put("similarity", 0.5);
                        item.put("date", dateCache.get(mid));
                        items.add(item);
                        meetingsInResults.add(mid);
                    }
                }
            }

            if (items.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未找到相关结果。"));
            }

            // Sort by similarity descending, limit to topK
            items.sort((a, b) -> Double.compare(
                    ((Number) b.get("similarity")).doubleValue(),
                    ((Number) a.get("similarity")).doubleValue()));
            if (items.size() > topK) {
                items = items.subList(0, topK);
            }

            return Mono.just(ToolResultBlock.text(toJson(items)));
        } catch (Exception e) {
            log.warn("Knowledge base search failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("搜索知识库异常，请稍后重试"));
        }
    }

    private List<Long> searchVectorContent(String keyword, int limit) {
        try {
            String sql = "SELECT DISTINCT meeting_id FROM meeting_vectors WHERE content ILIKE ? LIMIT ?";
            String pattern = "%" + keyword + "%";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pattern, limit);
            return rows.stream()
                    .map(r -> ((Number) r.get("meeting_id")).longValue())
                    .toList();
        } catch (Exception e) {
            log.warn("Vector content ILIKE search failed for '{}': {}", keyword, e.getMessage());
            return List.of();
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
