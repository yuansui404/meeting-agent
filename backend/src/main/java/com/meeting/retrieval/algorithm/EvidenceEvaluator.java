package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EvidenceEvaluator {

    /**
     * 评估检索结果的充分性。
     *
     * <p>核心逻辑：两种独立检索方法（向量 + 全文）是否<strong>收敛</strong>到同一份文档。
     * 收敛意味着两种方法的独立排名机制都认为该文档与查询相关，是检索充分的强信号。</p>
     *
     * <ul>
     *   <li>SUFFICIENT — 两种检索都命中且收敛到同一份文档</li>
     *   <li>PARTIAL — 两种检索都命中但指向不同文档 / 仅一种检索命中但来自多份文档</li>
     *   <li>WEAK — 仅一种检索命中且来自单份文档</li>
     *   <li>NONE — 均无结果</li>
     * </ul>
     *
     * @param topChunks     RRF 融合后的最终结果列表（用于单方法分支）
     * @param vectorResults 向量检索的原始结果
     * @param ftsResults    全文检索的原始结果
     * @return 证据等级
     */
    public EvidenceLevel evaluate(List<ChunkResult> topChunks,
                                  List<ChunkResult> vectorResults,
                                  List<ChunkResult> ftsResults) {
        boolean hasVector = !vectorResults.isEmpty();
        boolean hasFts = !ftsResults.isEmpty();

        if (!hasVector && !hasFts) return EvidenceLevel.NONE;

        // 两种检索都命中 → 检查是否收敛到同一文档
        if (hasVector && hasFts) {
            Set<Long> ftsDocs = ftsResults.stream()
                    .map(ChunkResult::getDocumentId)
                    .collect(Collectors.toSet());

            boolean converged = vectorResults.stream()
                    .map(ChunkResult::getDocumentId)
                    .anyMatch(ftsDocs::contains);

            if (converged) return EvidenceLevel.SUFFICIENT;
            return EvidenceLevel.PARTIAL;
        }

        // 只有一种检索命中
        List<ChunkResult> hits = hasVector ? vectorResults : ftsResults;
        long distinctDocs = hits.stream()
                .map(ChunkResult::getDocumentId).distinct().count();
        return distinctDocs >= 2 ? EvidenceLevel.PARTIAL : EvidenceLevel.WEAK;
    }
}