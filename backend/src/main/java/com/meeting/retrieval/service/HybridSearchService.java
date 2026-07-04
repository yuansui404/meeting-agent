package com.meeting.retrieval.service;

import com.meeting.common.TtlMdcAdapter;
import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.algorithm.CitationBuilder;
import com.meeting.retrieval.algorithm.DocumentDeduplicator;
import com.meeting.retrieval.algorithm.EvidenceEvaluator;
import com.meeting.retrieval.algorithm.RrfMerger;
import com.meeting.retrieval.algorithm.TimeDecayScorer;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import com.meeting.retrieval.model.EvidenceLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final RrfMerger rrfMerger;
    private final TimeDecayScorer timeDecayScorer;
    private final DocumentDeduplicator documentDeduplicator;
    private final EvidenceEvaluator evidenceEvaluator;
    private final CitationBuilder citationBuilder;
    private final RagProperties ragProperties;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor taskExecutor;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Citation> citations,
            String queryUsed,
            boolean retried
    ) {}

    public SearchResult search(String query, String timeRange) {
        TtlMdcAdapter.setLayer("RETRIEVAL");
        try {
            QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
            log.info("Query plan: strategy={}, rewritten={}", plan.strategy(), plan.rewrittenQuery());

            boolean retried = false;
            String actualQuery = plan.rewrittenQuery();

            // Step 1: Execute searches (supports DECOMPOSE with multiple sub-queries)
            List<ChunkResult> vectorResults = new ArrayList<>();
            List<ChunkResult> ftsResults = new ArrayList<>();
            for (String subQuery : plan.subQueries()) {
                CompletableFuture<List<ChunkResult>> vectorF = CompletableFuture.supplyAsync(() ->
                        vectorSearchService.search(subQuery, ragProperties.getRetrieval().getVectorTopk()), taskExecutor);
                CompletableFuture<List<ChunkResult>> ftsF = CompletableFuture.supplyAsync(() ->
                        fullTextSearchService.search(subQuery, ragProperties.getRetrieval().getFtsTopk()), taskExecutor);
                vectorResults.addAll(vectorF.join());
                ftsResults.addAll(ftsF.join());
            }

            // Step 2: RRF merge
            List<ChunkResult> merged = rrfMerger.merge(vectorResults, ftsResults, ragProperties.getRetrieval().getRrfK());

            // Step 3: Rerank
            if (ragProperties.getRetrieval().isRerankEnabled()) {
                merged = reranker.reRank(actualQuery, merged, ragProperties.getRetrieval().getRerankTopk());
            }

            // Step 4: Time decay (batch fetch documents to avoid N+1)
            merged = applyTimeDecay(merged);

            // Step 5: Deduplicate
            merged = documentDeduplicator.deduplicate(merged, 5);

            // Step 6: Retry if low confidence
            double topScore = merged.isEmpty() ? 0.0 : merged.get(0).getFinalScore();
            if (topScore < ragProperties.getEvidence().getThreshold()) {
                log.info("Low confidence (topScore={}), triggering retry", topScore);
                String retryQuery = generateRetryQuery(actualQuery);
                List<ChunkResult> retryMerged = executeSearch(retryQuery);
                retryMerged = applyTimeDecay(retryMerged);
                retryMerged = documentDeduplicator.deduplicate(retryMerged, 5);

                double retryTopScore = retryMerged.isEmpty() ? 0.0 : retryMerged.get(0).getFinalScore();
                if (retryTopScore > topScore) {
                    merged = retryMerged;
                    actualQuery = retryQuery;
                    retried = true;
                }
            }

            // Step 7: Expand neighbors
            merged = expandNeighbors(merged);

            // Step 8: Evaluate evidence
            EvidenceLevel evidenceLevel = evidenceEvaluator.evaluate(merged, vectorResults.size(), ftsResults.size());
            List<Citation> citations = citationBuilder.build(merged);

            log.info("Hybrid search complete: query={}, chunks={}, evidence={}",
                    actualQuery, merged.size(), evidenceLevel);

            return new SearchResult(merged, evidenceLevel.name(), citations, actualQuery, retried);
        } finally {
            TtlMdcAdapter.remove("layer");
        }
    }

    private List<ChunkResult> executeSearch(String query) {
        CompletableFuture<List<ChunkResult>> vectorF = CompletableFuture.supplyAsync(() ->
                vectorSearchService.search(query, ragProperties.getRetrieval().getVectorTopk()), taskExecutor);
        CompletableFuture<List<ChunkResult>> ftsF = CompletableFuture.supplyAsync(() ->
                fullTextSearchService.search(query, ragProperties.getRetrieval().getFtsTopk()), taskExecutor);
        return rrfMerger.merge(vectorF.join(), ftsF.join(), ragProperties.getRetrieval().getRrfK());
    }

    private List<ChunkResult> applyTimeDecay(List<ChunkResult> chunks) {
        if (!ragProperties.getTimeDecay().isEnabled() || chunks.isEmpty()) {
            for (ChunkResult r : chunks) {
                r.setFinalScore(r.getFtsScore() > 0 ? r.getRrfScore() : r.getVectorScore());
            }
            return chunks;
        }

        // Batch fetch all documents to avoid N+1 queries
        Set<Long> docIds = chunks.stream()
                .map(ChunkResult::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DocumentEntity> docMap = new HashMap<>();
        if (!docIds.isEmpty()) {
            documentRepository.findAllById(docIds).forEach(doc -> docMap.put(doc.getId(), doc));
        }

        var config = ragProperties.getTimeDecay();
        for (ChunkResult r : chunks) {
            double baseScore = r.getFtsScore() > 0 ? r.getRrfScore() : r.getVectorScore();
            DocumentEntity doc = docMap.get(r.getDocumentId());
            LocalDate meetingDate = doc != null ? doc.getMeetingDate() : null;
            r.setFinalScore(timeDecayScorer.apply(baseScore, meetingDate,
                    new TimeDecayScorer.TimeDecayConfig(
                            true, config.getRecentDays(), config.getRecentWeight(),
                            config.getNormalWeight(), config.getOldWeight(), config.getArchiveWeight()
                    )));
        }
        return chunks;
    }

    String generateRetryQuery(String originalQuery) {
        return originalQuery + " 内容 详情 决定";
    }

    List<ChunkResult> expandNeighbors(List<ChunkResult> results) {
        List<ChunkResult> expanded = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

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
