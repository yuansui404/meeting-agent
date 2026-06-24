package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;

import java.util.*;

public class CitationBuilder {

    public static List<Map<String, String>> build(List<ChunkResult> chunks) {
        Map<String, Map<String, String>> citationMap = new LinkedHashMap<>();
        int sourceId = 0;
        for (ChunkResult r : chunks) {
            citationMap.putIfAbsent(r.getFileName(), Map.of(
                    "source_id", String.valueOf(++sourceId),
                    "file_name", r.getFileName(),
                    "content", r.getContent().length() > 100
                            ? r.getContent().substring(0, 100) + "..."
                            : r.getContent()
            ));
        }
        return new ArrayList<>(citationMap.values());
    }
}
