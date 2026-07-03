package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.service.HybridSearchService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

@Component
public class SearchDocumentsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentsTool.class);

    private final HybridSearchService hybridSearchService;

    public SearchDocumentsTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public String getDescription() {
        return "搜索知识库中的文档/文件内容。支持语义搜索+全文检索融合，返回带证据等级(evidenceLevel)和引文(citations)的结构化结果。当用户查询文档中的具体内容时使用。支持可选参数 timeRange 限定时间范围，如\"最近30天\"。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词，尽量简洁准确"
        ));
        properties.put("timeRange", Map.of(
                "type", "string",
                "description", "可选，时间范围限定，如\"最近30天\"、\"最近90天\"、\"今年\"，不传则不限时间"
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
            return Mono.just(ToolResultBlock.text("{\"evidenceLevel\":\"NONE\",\"results\":[],\"citations\":[],\"queryUsed\":\"\",\"retried\":false}"));
        }

        String timeRange = null;
        Object timeRangeObj = input.get("timeRange");
        if (timeRangeObj instanceof String s && !s.isBlank()) {
            timeRange = s;
        }

        try {
            HybridSearchService.SearchResult result = hybridSearchService.search(query, timeRange);

            if (result.chunks().isEmpty()) {
                return Mono.just(ToolResultBlock.text(JsonUtil.toJson(Map.of(
                        "evidenceLevel", result.evidenceLevel(),
                        "results", List.of(),
                        "citations", result.citations(),
                        "queryUsed", result.queryUsed(),
                        "retried", result.retried()
                ))));
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (ChunkResult chunk : result.chunks()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", chunk.getFileName() != null ? chunk.getFileName() : "未知文档");
                item.put("content", chunk.getContent() != null ? chunk.getContent() : "");
                item.put("score", Math.round(chunk.getFinalScore() * 100.0) / 100.0);
                item.put("speaker", chunk.getSpeaker() != null ? chunk.getSpeaker() : "");
                item.put("sectionType", chunk.getSectionType() != null ? chunk.getSectionType() : "");
                items.add(item);
            }

            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("evidenceLevel", result.evidenceLevel());
            toolResult.put("results", items);
            toolResult.put("citations", result.citations());
            toolResult.put("queryUsed", result.queryUsed());
            toolResult.put("retried", result.retried());

            return Mono.just(ToolResultBlock.text(JsonUtil.toJson(toolResult)));
        } catch (Exception e) {
            log.warn("Document search failed", e);
            return Mono.just(ToolResultBlock.error("{\"evidenceLevel\":\"NONE\",\"results\":[],\"citations\":[],\"error\":\"搜索异常，请稍后重试\"}"));
        }
    }

}
