package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.repository.FullTextSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FullTextSearchService {

    private final FullTextSearchRepository fullTextSearchRepository;

    public List<ChunkResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            log.debug("Full-text search skipped: empty query");
            return Collections.emptyList();
        }
        int effectiveTopK = Math.max(1, Math.min(topK, 100));
        List<ChunkResult> results = fullTextSearchRepository.search(query, effectiveTopK);
        log.debug("Full-text search: query={}, topK={}, found={}", query, effectiveTopK, results.size());
        return results;
    }
}
