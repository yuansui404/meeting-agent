package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;

import java.util.*;
import java.util.stream.Collectors;

public class RrfMerger {

    public static List<ChunkResult> merge(List<ChunkResult> vector, List<ChunkResult> fts, int k) {
        Map<Long, ChunkResult> merged = new LinkedHashMap<>();

        for (ChunkResult r : vector) {
            r.setRrfScore(1.0 / (k + r.getVectorRank()));
            merged.put(r.getChunkId(), r);
        }

        for (ChunkResult r : fts) {
            merged.computeIfAbsent(r.getChunkId(), id -> r);
            merged.get(r.getChunkId()).setFtsScore(r.getFtsScore());
            merged.get(r.getChunkId()).setRrfScore(
                    merged.get(r.getChunkId()).getRrfScore() + 1.0 / (k + r.getFtsRank())
            );
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ChunkResult::getRrfScore).reversed())
                .collect(Collectors.toList());
    }
}
