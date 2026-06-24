package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(value = "rag.retrieval.rerank-enabled", havingValue = "false", matchIfMissing = true)
public class NoOpReranker implements Reranker {
    @Override
    public List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN) {
        return candidates.stream().limit(topN).collect(Collectors.toList());
    }
}
