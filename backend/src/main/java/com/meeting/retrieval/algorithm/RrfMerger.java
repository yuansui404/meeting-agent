package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class RrfMerger {

    public List<ChunkResult> merge(List<ChunkResult> vector, List<ChunkResult> fts, int k) {
        Map<Long, ChunkResult> merged = new LinkedHashMap<>();
        Set<Long> vectorHits = new HashSet<>();

        for (ChunkResult r : vector) {
            vectorHits.add(r.getChunkId());
            r.setRrfScore(r.getVectorRank() != null ? 1.0 / (k + r.getVectorRank()) : 0);
            merged.put(r.getChunkId(), r);
        }

        for (ChunkResult r : fts) {
            merged.computeIfAbsent(r.getChunkId(), id -> r);
            ChunkResult existing = merged.get(r.getChunkId());
            existing.setFtsScore(r.getFtsScore());
            if (r.getFtsRank() != null) {
                existing.setRrfScore(existing.getRrfScore() + 1.0 / (k + r.getFtsRank()));
            }
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ChunkResult::getRrfScore).reversed())
                .collect(Collectors.toList());
    }
}
