package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.service.HybridSearchService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchDocumentsTool implements AgentTool {

    private final HybridSearchService hybridSearchService;

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public String getDescription() {
        return "搜索知识库中的文档/文件内容。支持语义搜索+全文检索融合，返回带证据等级(evidenceLevel)和引文(citations)的结构化结果。"
                + "当用户查询文档中的具体内容时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "搜索关键词，尽量简洁准确。注意：如果用户问题中包含代词（它、他、她、这、那、该等），请先结合对话历史替换为具体的人名/会议名/主题，再传入此参数"
                )),
                "required", List.of("query")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String query = input.getOrDefault("query", "").toString();
            if (query.isBlank()) {
                return ToolResultBlock.text("{\"evidenceLevel\":\"NONE\",\"strategyUsed\":\"\",\"totalCandidates\":0,\"results\":[],\"citations\":[],\"queryUsed\":\"\"}");
            }

            HybridSearchService.SearchResult result = hybridSearchService.search(query);

            SearchToolResponse response = buildResponse(result);
            return ToolResultBlock.text(JsonUtil.toJson(response));
        });
    }

    private SearchToolResponse buildResponse(HybridSearchService.SearchResult result) {
        List<SearchToolResponse.ChunkItem> items = new ArrayList<>();
        for (ChunkResult chunk : result.chunks()) {
            items.add(SearchToolResponse.ChunkItem.builder()
                    .source(chunk.getFileName() != null ? chunk.getFileName() : "未知文档")
                    .content(chunk.getContent() != null ? chunk.getContent() : "")
                    .speaker(chunk.getSpeaker() != null ? chunk.getSpeaker() : "")
                    .participants(chunk.getParticipants() != null ? chunk.getParticipants() : "")
                    .topic(chunk.getTopic() != null ? chunk.getTopic() : "")
                    .sectionHeading(chunk.getSectionHeading() != null ? chunk.getSectionHeading() : "")
                    .build());
        }

        return SearchToolResponse.builder()
                .evidenceLevel(result.evidenceLevel())
                .strategyUsed(result.strategyUsed())
                .totalCandidates(result.totalCandidates())
                .results(items)
                .citations(result.citations())
                .queryUsed(result.queryUsed())
                .build();
    }

}
