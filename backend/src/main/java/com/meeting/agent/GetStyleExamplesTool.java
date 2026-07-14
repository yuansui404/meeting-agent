package com.meeting.agent;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.llm.service.EmbeddingService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetStyleExamplesTool implements AgentTool {

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    @Override
    public String getName() {
        return "get_style_examples";
    }

    @Override
    public String getDescription() {
        return "搜索与原文风格相似的文档，返回其 .cleaned.md 内容作为写作风格参考。参数：原文内容或查询关键词";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "需要匹配风格的原文内容或查询关键词"
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
                return ToolResultBlock.text("请提供需要匹配风格的原文内容或查询关键词");
            }

            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = "[" + floatArrayToString(queryEmbedding) + "]";

            List<VectorSearchHit> hits = chunkRepository.styleExemplarSearch(embeddingStr, 5);
            if (hits.isEmpty()) {
                return ToolResultBlock.text("未找到相似风格参考文档");
            }

            List<Long> docIds = hits.stream()
                    .map(VectorSearchHit::documentId)
                    .distinct()
                    .limit(3)
                    .toList();

            StringBuilder sb = new StringBuilder("以下是与您内容风格相似的文档参考（.cleaned.md）：\n\n");
            boolean hasContent = false;
            for (Long docId : docIds) {
                DocumentEntity doc = documentRepository.findById(docId).orElse(null);
                if (doc == null) continue;

                String mdContent = readCleanedMd(doc);
                if (mdContent == null) continue;

                hasContent = true;
                sb.append("【参考文档：").append(doc.getTitle()).append("】\n");
                if (mdContent.length() > 3000) {
                    mdContent = mdContent.substring(0, 3000) + "\n...（内容较长，仅展示前 3000 字符）";
                }
                sb.append(mdContent).append("\n\n---\n\n");
            }

            if (!hasContent) {
                return ToolResultBlock.text("找到相似文档但无法读取 .cleaned.md 文件内容");
            }

            sb.append("请严格模仿以上参考文档的写作风格、语气和结构进行改写。");
            return ToolResultBlock.text(sb.toString());
        });
    }

    private String readCleanedMd(DocumentEntity doc) {
        if (doc.getMdFilePath() != null) {
            Path mdPath = Path.of(doc.getMdFilePath());
            if (Files.exists(mdPath)) {
                try {
                    return Files.readString(mdPath);
                } catch (IOException e) {
                    log.warn("Failed to read .cleaned.md for doc {}: {}", doc.getId(), e.getMessage());
                }
            }
        }
        return null;
    }

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}