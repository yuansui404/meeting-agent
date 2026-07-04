package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CitationBuilder {

    public List<Citation> build(List<ChunkResult> chunks) {
        Map<String, Citation> citationMap = new LinkedHashMap<>();
        int sourceId = 0;
        for (ChunkResult r : chunks) {
            citationMap.putIfAbsent(r.getFileName(), Citation.builder()
                    .sourceId(++sourceId)
                    .fileName(r.getFileName())
                    .content(r.getContent() != null && r.getContent().length() > 100
                            ? r.getContent().substring(0, 100) + "..."
                            : r.getContent())
                    .build());
        }
        return new ArrayList<>(citationMap.values());
    }
}
