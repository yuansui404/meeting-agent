package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.repository.DocumentChunkV2Repository;
import com.meeting.retrieval.model.ChunkMetadataParser;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.config.RagProperties;
import com.meeting.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量搜索服务。
 *
 * 流程：query → EmbeddingService 生成向量 → float[] 转 pgvector 字面量 →
 *       PostgreSQL 余弦距离最近邻检索 → 解析 metadata → 返回 ChunkResult
 *
 * 注意：metadata 过滤由 SearchPipelineExecutor 在管线层统一处理，
 *       本服务仅负责语义召回，不参与过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final DocumentChunkV2Repository chunkV2Repository;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    /**
     * @param query 用户查询文本
     * @param topK  返回 topK 条结果
     */
    public List<ChunkResult> search(String query, int topK) {
        // 1. 将用户查询文本（原始问题）转为 1024 维向量
        float[] queryVector = embeddingService.generateEmbedding(query);

        // 2. float[] 转 pgvector 字符串格式 "[0.023,-0.015,0.107,...]"
        //    pgvector 的 CAST(:embedding AS vector) 要求此格式
        String embeddingStr = floatArrayToString(queryVector);
        String vectorStr = "[" + embeddingStr + "]";

        // 3. 执行原生 SQL 余弦距离搜索，返回最近邻 topK 条
        List<VectorSearchHit> hits = chunkV2Repository.vectorSearch(vectorStr, topK);

        // 4. 动态阈值过滤：硬底线 + 相对落差双保险，低分 Chunk 不进后续管线
        RagProperties.Retrieval retrievalConfig = ragProperties.getRetrieval();
        double floor = retrievalConfig.getVectorSimilarityFloor();
        double drop = retrievalConfig.getVectorSimilarityDrop();
        int originalSize = hits.size();
        if (originalSize > 0) {
            double maxScore = hits.stream()
                    .mapToDouble(VectorSearchHit::similarityScore)
                    .max()
                    .orElse(0);
            double threshold = Math.max(floor, maxScore - drop);
            hits = hits.stream()
                    .filter(h -> h.similarityScore() >= threshold)
                    .toList();
            log.info("Vector search: query={}, filtered {}→{} hits (threshold={})",
                    query, originalSize, hits.size(), String.format("%.4f", threshold));
        }

        // 5. 将查询结果转为 ChunkResult，同时解析 metadata JSON 提取结构化字段
        List<ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            VectorSearchHit hit = hits.get(i);
            var parsed = ChunkMetadataParser.parse(hit.metadata(), objectMapper);

            results.add(ChunkResult.builder()
                    .chunkId(hit.id())
                    .documentId(hit.documentId())
                    .content(hit.content())
                    .chunkIndex(hit.chunkIndex())
                    .speaker(hit.speaker())
                    .fileName(parsed.fileName())
                    .meetingDate(parsed.meetingDate())
                    .participants(parsed.participants())
                    .topic(parsed.topic())
                    .sectionHeading(parsed.sectionHeading())
                    .vectorScore(hit.similarityScore())
                    .vectorRank(i + 1)          // 按相似度降序排列的序号
                    .build());
        }

        log.debug("Vector search: query={}, topK={}, found={}", query, topK, results.size());
        return results;
    }

    /** float[] 转逗号分隔字符串，供拼装 pgvector 字面量 */
    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
