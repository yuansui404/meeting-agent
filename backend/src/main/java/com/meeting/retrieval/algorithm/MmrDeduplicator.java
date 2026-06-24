package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;

import java.util.*;

public class MmrDeduplicator {

    public static List<ChunkResult> deduplicate(List<ChunkResult> results, int topN) {
        if (results.size() <= topN) return results;

        List<ChunkResult> selected = new ArrayList<>();
        Set<Long> selectedDocs = new HashSet<>();

        for (ChunkResult r : results) {
            if (selected.size() >= topN) break;
            if (selectedDocs.contains(r.getDocumentId())) continue;
            selected.add(r);
            selectedDocs.add(r.getDocumentId());
        }

        return selected;
    }
}
