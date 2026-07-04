package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.meeting.repository.MeetingVectorRepository;
import com.meeting.service.QueryRewriter;
import com.meeting.service.VectorizationService;
import com.meeting.service.VectorizationService.ScoredVector;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final MeetingVectorRepository meetingVectorRepository;
    private final QueryRewriter queryRewriter;

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
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String query = input.getOrDefault("query", "").toString();
            if (query.isBlank()) {
                return ToolResultBlock.text("搜索关键词不能为空");
            }

            int topK = 10;
            Object topKObj = input.get("topK");
            if (topKObj instanceof Number n) {
                topK = Math.max(1, Math.min(20, n.intValue()));
            }

            // 1. Vector search with original query (semantic matching)
            List<ScoredVector> vectorResults = vectorizationService.searchSimilarWithScores(query, topK);

            // 2. Extract keywords and do precise matching
            List<String> keywords = queryRewriter.extractKeywords(query);

            // 3. Batch-load all meeting metadata to avoid N+1 queries
            Set<Long> allMeetingIds = new HashSet<>();
            for (ScoredVector v : vectorResults) allMeetingIds.add(v.meetingId());

            // Cache participant search results to avoid duplicate queries
            Map<String, List<MeetingMinutes>> participantResults = new HashMap<>();
            for (String keyword : keywords) {
                List<MeetingMinutes> results = meetingRepository.searchByParticipants(keyword);
                participantResults.put(keyword, results);
                for (MeetingMinutes mm : results) {
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
                List<MeetingMinutes> byParticipants = participantResults.getOrDefault(keyword, List.of());
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

                List<Long> contentMatchIds = meetingVectorRepository.findMeetingIdsByContentLike(keyword, topK);
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
                return ToolResultBlock.text("未找到相关结果。");
            }

            // Sort by similarity descending, limit to topK
            items.sort((a, b) -> Double.compare(toDouble(b.get("similarity")), toDouble(a.get("similarity"))));
            if (items.size() > topK) {
                items = items.subList(0, topK);
            }

            return ToolResultBlock.text(JsonUtil.toJsonArray(items));
        });
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
