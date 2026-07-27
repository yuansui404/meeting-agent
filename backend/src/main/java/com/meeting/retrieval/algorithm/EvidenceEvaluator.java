package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class EvidenceEvaluator {

    /**
     * 评估检索结果的充分性。
     *
     * <p>基于 rerank 位置启发式：top-5 中同一文档出现 ≥2 个 chunk，
     * 说明 reranker 确认了该文档的相关性，判定为 SUFFICIENT。</p>
     *
     * @param rerankedTop5 rerank 后的 top-N 结果
     * @return 证据等级
     */
    public EvidenceLevel evaluate(List<ChunkResult> rerankedTop5) {
        if (rerankedTop5 == null || rerankedTop5.isEmpty()) {
            return EvidenceLevel.NONE;
        }

        // rerank 位置启发式：top-5 中同一文档出现 ≥2 个 chunk → 确认
        boolean rerankConfident = rerankedTop5.stream()
                .collect(Collectors.groupingBy(
                        ChunkResult::getDocumentId, Collectors.counting()))
                .values().stream()
                .anyMatch(count -> count >= 2);

        return rerankConfident ? EvidenceLevel.SUFFICIENT : EvidenceLevel.PARTIAL;
    }
}