package com.meeting.retrieval.service;

import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.algorithm.*;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorSearchService vectorSearchService;
    private final FullTextSearchService fullTextSearchService;
    private final QueryPlanningService queryPlanningService;
    private final Reranker reranker;
    private final RagProperties ragProperties;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Map<String, String>> citations,
            String queryUsed,
            boolean retried
    ) {}

    public SearchResult search(String query, String timeRange) {
        MDC.put("layer", "RETRIEVAL");

        QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
        log.info("Query plan: strategy={}, rewritten={}", plan.strategy(), plan.rewrittenQuery());

        String actualQuery = plan.rewrittenQuery();
        boolean retried = false;

        List<ChunkResult> vectorResults = vectorSearchService.search(actualQuery, ragProperties.getRetrieval().getVectorTopk());
        List<ChunkResult> ftsResults = fullTextSearchService.search(actualQuery, ragProperties.getRetrieval().getFtsTopk());

        List<ChunkResult> merged = RrfMerger.merge(vectorResults, ftsResults, ragProperties.getRetrieval().getRrfK());

        if (ragProperties.getRetrieval().isRerankEnabled()) {
            merged = reranker.reRank(actualQuery, merged, ragProperties.getRetrieval().getRerankTopk());
        }

        var timeDecayConfig = ragProperties.getTimeDecay();
        for (ChunkResult r : merged) {
            // When FTS doesn't contribute, use vector score directly (meaningful similarity)
            // When FTS contributes, use combined RRF score
            double baseScore = (r.getFtsScore() > 0)
                    ? r.getRrfScore()
                    : r.getVectorScore();

            if (ragProperties.getTimeDecay().isEnabled() && r.getDocumentId() != null) {
                try {
                    DocumentEntity doc = documentRepository.findById(r.getDocumentId()).orElse(null);
                    LocalDate meetingDate = doc != null ? doc.getMeetingDate() : null;
                    r.setFinalScore(TimeDecayScorer.apply(baseScore, meetingDate,
                            new TimeDecayScorer.TimeDecayConfig(
                                    true, timeDecayConfig.getRecentDays(), timeDecayConfig.getRecentWeight(),
                                    timeDecayConfig.getNormalWeight(), timeDecayConfig.getOldWeight(), timeDecayConfig.getArchiveWeight()
                            )));
                } catch (Exception e) {
                    log.warn("Time decay calculation failed for chunk {}", r.getChunkId(), e);
                    r.setFinalScore(baseScore);
                }
            } else {
                r.setFinalScore(baseScore);
            }
        }

        merged = MmrDeduplicator.deduplicate(merged, 5);

        double topScore = merged.isEmpty() ? 0.0 : merged.get(0).getFinalScore();
        if (topScore < ragProperties.getEvidence().getThreshold()) {
            log.info("Low confidence, triggering retry");
            String retryQuery = generateRetryQuery(actualQuery);
            List<ChunkResult> retryVector = vectorSearchService.search(retryQuery, ragProperties.getRetrieval().getVectorTopk());
            List<ChunkResult> retryFts = fullTextSearchService.search(retryQuery, ragProperties.getRetrieval().getFtsTopk());
            List<ChunkResult> retryMerged = RrfMerger.merge(retryVector, retryFts, ragProperties.getRetrieval().getRrfK());
            double retryTopScore = retryMerged.isEmpty() ? 0.0 : retryMerged.get(0).getRrfScore();
            if (retryTopScore > topScore) {
                merged = retryMerged;
            }
            retried = true;
            actualQuery = retryQuery;
        }

        merged = expandNeighbors(merged);

        EvidenceLevel evidenceLevel = EvidenceEvaluator.evaluate(merged, vectorResults.size(), ftsResults.size());
        List<Map<String, String>> citations = CitationBuilder.build(merged);

        log.info("Hybrid search complete: query={}, chunks={}, evidence={}",
                actualQuery, merged.size(), evidenceLevel);

        return new SearchResult(merged, evidenceLevel.name(), citations, actualQuery, retried);
    }

    String generateRetryQuery(String originalQuery) {
        return originalQuery + " 内容 详情 决定";
    }

    List<ChunkResult> expandNeighbors(List<ChunkResult> results) {
        List<ChunkResult> expanded = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (ChunkResult r : results) {
            expanded.add(r);
            seenIds.add(r.getChunkId());
            if (r.getDocumentId() == null) continue;
            try {
                var neighbors = chunkRepository.findByDocumentIdOrderByChunkIndex(r.getDocumentId());
                for (var n : neighbors) {
                    if (Math.abs(n.getChunkIndex() - r.getChunkIndex()) <= 1
                            && !seenIds.contains(n.getId())) {
                        seenIds.add(n.getId());
                        expanded.add(ChunkResult.builder()
                                .chunkId(n.getId())
                                .documentId(n.getDocumentId())
                                .content(n.getContent())
                                .chunkIndex(n.getChunkIndex())
                                .speaker(n.getSpeaker())
                                .sectionType(n.getSectionType())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Neighbor expansion failed for chunk {}", r.getChunkId(), e);
            }
        }
        return expanded;
    }
}
