package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;

import java.util.List;

public interface Reranker {
    List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN);
}
