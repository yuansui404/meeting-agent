package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EvidenceEvaluator {

    public EvidenceLevel evaluate(List<ChunkResult> topChunks, int vectorCount, int ftsCount) {
        boolean hasVector = vectorCount > 0;
        boolean hasFts = ftsCount > 0;
        long distinctDocs = topChunks.stream()
                .map(ChunkResult::getDocumentId).distinct().count();

        if (!hasVector && !hasFts) return EvidenceLevel.NONE;
        if (!hasVector || !hasFts) {
            return distinctDocs >= 2 ? EvidenceLevel.PARTIAL : EvidenceLevel.WEAK;
        }
        if (hasVector && hasFts && distinctDocs >= 2) return EvidenceLevel.SUFFICIENT;
        return EvidenceLevel.PARTIAL;
    }
}
