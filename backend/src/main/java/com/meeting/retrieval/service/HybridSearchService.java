package com.meeting.retrieval.service;

import com.meeting.common.TtlMdcAdapter;
import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.retrieval.algorithm.CitationBuilder;
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
    private final EvidenceEvaluator evidenceEvaluator;
    private final CitationBuilder citationBuilder;
    private final RagProperties ragProperties;
    private final DocumentChunkRepository chunkRepository;
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor taskExecutor;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Citation> citations,
            String queryUsed,
            boolean retried,
            double topScore,
            String strategyUsed,
            int totalCandidates
    ) {}

    public SearchResult search(String query, String timeRange) {
        // 日志用
        TtlMdcAdapter.setLayer("RETRIEVAL");
        try {
            QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
            log.info("Query plan: strategy={}, rewritten={}, timeIntent={}, timeRange={}",
                    plan.strategy(), plan.rewrittenQuery(), plan.timeIntent(), plan.timeRange());

            boolean retried = false;
            String actualQuery = plan.rewrittenQuery(); // 读取plan的rewrittenQuery字段

            // Step 1: Execute searches (all sub-queries in parallel)
            List<CompletableFuture<List<ChunkResult>>> vectorFutures = new ArrayList<>(); //向量检索汇总
            List<CompletableFuture<List<ChunkResult>>> ftsFutures = new ArrayList<>(); // 全文检索汇总
            for (String subQuery : plan.subQueries()) {
                vectorFutures.add(CompletableFuture.supplyAsync(() ->
                        vectorSearchService.search(subQuery, ragProperties.getRetrieval().getVectorTopk()), taskExecutor));
                ftsFutures.add(CompletableFuture.supplyAsync(() ->
                        fullTextSearchService.search(subQuery, ragProperties.getRetrieval().getFtsTopk()), taskExecutor));
            }
            List<ChunkResult> vectorResults = new ArrayList<>();
            List<ChunkResult> ftsResults = new ArrayList<>();
            for (CompletableFuture<List<ChunkResult>> f : vectorFutures) vectorResults.addAll(f.join());
            for (CompletableFuture<List<ChunkResult>> f : ftsFutures) ftsResults.addAll(f.join());

            // Step 2: RRF merge
            List<ChunkResult> merged = rrfMerger.merge(vectorResults, ftsResults, ragProperties.getRetrieval().getRrfK());

            // Step 3: Rerank
            if (ragProperties.getRetrieval().isRerankEnabled()) {
                merged = reranker.reRank(actualQuery, merged, ragProperties.getRetrieval().getRerankTopk());
            }

            // Step 4: Time decay (conditionally applied based on query time intent)
            merged = applyDecayWithIntent(merged, plan.timeIntent(), plan.timeRange());

            // Step 5: Expand neighbors (removed document deduplication — same document chunks are complementary, not redundant)
            int totalCandidates = merged.size();
            merged = expandNeighbors(merged);

            // Step 6: Retry if low confidence
            double topScore = merged.isEmpty() ? 0.0 : merged.get(0).getFinalScore();
            if (topScore < ragProperties.getEvidence().getThreshold()) {
                log.info("Low confidence (topScore={}), triggering retry", topScore);
                String retryQuery = generateRetryQuery(actualQuery);
                List<ChunkResult> retryMerged = executeSearch(retryQuery);
                retryMerged = applyDecayWithIntent(retryMerged, plan.timeIntent(), plan.timeRange());
                totalCandidates = retryMerged.size();
                double retryTopScore = retryMerged.isEmpty() ? 0.0 : retryMerged.get(0).getFinalScore();
                if (retryTopScore > topScore) {
                    merged = retryMerged;
                    actualQuery = retryQuery;
                    retried = true;
                }
            }

            // Step 8: Evaluate evidence
            double finalTopScore = merged.isEmpty() ? 0.0 : merged.get(0).getFinalScore();
            EvidenceLevel evidenceLevel = evidenceEvaluator.evaluate(merged, vectorResults.size(), ftsResults.size());
            List<Citation> citations = citationBuilder.build(merged);

            log.info("Hybrid search complete: query={}, chunks={}, evidence={}, strategy={}",
                    actualQuery, merged.size(), evidenceLevel, plan.strategy());

            return new SearchResult(merged, evidenceLevel.name(), citations, actualQuery, retried, finalTopScore, plan.strategy(), totalCandidates);
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
        if (chunks.isEmpty()) return chunks;

        if (!ragProperties.getTimeDecay().isEnabled()) {
            for (ChunkResult r : chunks) {
                r.setFinalScore(r.getRrfScore());
            }
            return chunks;
        }

        var config = ragProperties.getTimeDecay();
        for (ChunkResult r : chunks) {
            r.setFinalScore(timeDecayScorer.apply(r.getRrfScore(), r.getMeetingDate(),
                    new TimeDecayScorer.TimeDecayConfig(
                            true, config.getRecentDays(), config.getRecentWeight(),
                            config.getNormalWeight(), config.getOldWeight(), config.getArchiveWeight()
                    )));
        }
        return chunks;
    }

    private List<ChunkResult> applyDecayWithIntent(List<ChunkResult> chunks,
                                                    QueryPlanningService.TimeIntent intent,
                                                    String timeRange) {
        return switch (intent) {
            case RECENT -> applyTimeDecay(chunks);
            case HISTORICAL, NEUTRAL, RANGE -> {
                // Skip time decay, preserve semantic ranking (RRF/Rerank score).
                // Historical queries should not be penalized for age.
                // Range queries need structured date parsing (future work).
                // Neutral queries have no time preference.
                if (chunks.isEmpty()) yield chunks;
                for (ChunkResult r : chunks) {
                    r.setFinalScore(r.getRrfScore());
                }
                yield chunks;
            }
        };
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
                            .build());
                }
            }
        }
        return expanded;
    }
}
