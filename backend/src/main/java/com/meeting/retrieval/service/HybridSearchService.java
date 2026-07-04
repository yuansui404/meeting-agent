package com.meeting.retrieval.service;

import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.algorithm.*;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import com.meeting.common.TtlMdcAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor taskExecutor;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Map<String, String>> citations,
            String queryUsed,
            boolean retried
    ) {}

    public SearchResult search(String query, String timeRange) {
        TtlMdcAdapter.setLayer("RETRIEVAL");
        try {
            QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
            log.info("Query plan: strategy={}, rewritten={}", plan.strategy(), plan.rewrittenQuery());

            String actualQuery = plan.rewrittenQuery();
            String searchQuery = actualQuery;
            int vectorTopK = ragProperties.getRetrieval().getVectorTopk();
            int ftsTopK = ragProperties.getRetrieval().getFtsTopk();
            boolean retried = false;

            CompletableFuture<List<ChunkResult>> vectorFuture = CompletableFuture.supplyAsync(() ->
                    vectorSearchService.search(searchQuery, vectorTopK), taskExecutor);
            CompletableFuture<List<ChunkResult>> ftsFuture = CompletableFuture.supplyAsync(() ->
                    fullTextSearchService.search(searchQuery, ftsTopK), taskExecutor);
            List<ChunkResult> vectorResults = vectorFuture.join();
            List<ChunkResult> ftsResults = ftsFuture.join();

            List<ChunkResult> merged = RrfMerger.merge(vectorResults, ftsResults, ragProperties.getRetrieval().getRrfK());

            if (ragProperties.getRetrieval().isRerankEnabled()) {
                merged = reranker.reRank(actualQuery, merged, ragProperties.getRetrieval().getRerankTopk());
            }

            var timeDecayConfig = ragProperties.getTimeDecay();
            for (ChunkResult r : merged) {
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
                CompletableFuture<List<ChunkResult>> retryVectorF = CompletableFuture.supplyAsync(() ->
                        vectorSearchService.search(retryQuery, ragProperties.getRetrieval().getVectorTopk()), taskExecutor);
                CompletableFuture<List<ChunkResult>> retryFtsF = CompletableFuture.supplyAsync(() ->
                        fullTextSearchService.search(retryQuery, ragProperties.getRetrieval().getFtsTopk()), taskExecutor);
                List<ChunkResult> retryVector = retryVectorF.join();
                List<ChunkResult> retryFts = retryFtsF.join();
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
        } finally {
            TtlMdcAdapter.remove("layer");
        }
    }

    String generateRetryQuery(String originalQuery) {
        return originalQuery + " 内容 详情 决定";
    }

    List<ChunkResult> expandNeighbors(List<ChunkResult> results) {
        List<ChunkResult> expanded = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        // Batch fetch all document chunks to avoid N+1 queries
        Map<Long, List<DocumentChunkEntity>> docChunks = new HashMap<>();
        Set<Long> docIds = results.stream()
                .map(ChunkResult::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!docIds.isEmpty()) {
            try {
                List<DocumentChunkEntity> allChunks = chunkRepository.findByDocumentIdInOrderByChunkIndex(new ArrayList<>(docIds));
                docChunks = allChunks.stream().collect(Collectors.groupingBy(DocumentChunkEntity::getDocumentId));
            } catch (Exception e) {
                log.warn("Batch neighbor fetch failed, falling back to individual queries", e);
            }
        }

        for (ChunkResult r : results) {
            expanded.add(r);
            seenIds.add(r.getChunkId());
            if (r.getDocumentId() == null) continue;

            List<DocumentChunkEntity> neighbors = docChunks.get(r.getDocumentId());
            if (neighbors == null) continue;

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
        }
        return expanded;
    }
}
