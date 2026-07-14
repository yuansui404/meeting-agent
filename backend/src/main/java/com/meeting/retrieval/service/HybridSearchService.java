package com.meeting.retrieval.service;

import com.meeting.common.TtlMdcAdapter;
import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.retrieval.algorithm.CitationBuilder;
import com.meeting.retrieval.algorithm.EvidenceEvaluator;
import com.meeting.retrieval.algorithm.RrfMerger;
import com.meeting.retrieval.model.ChunkMetadataParser;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import com.meeting.retrieval.model.EvidenceLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private final EvidenceEvaluator evidenceEvaluator;
    private final CitationBuilder citationBuilder;
    private final RagProperties ragProperties;
    private final DocumentChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor taskExecutor;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Citation> citations,
            String queryUsed,
            String strategyUsed,
            int totalCandidates
    ) {}

    public SearchResult search(String query) {
        // 日志用
        TtlMdcAdapter.setLayer("RETRIEVAL");
        try {
            QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
            log.info("Query plan: strategy={}, rewritten={}, timeIntent={}, timeRange={}",
                    plan.strategy(), plan.rewrittenQuery(), plan.timeIntent(), plan.timeRange());

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

            // Step 5: Evaluate evidence
            EvidenceLevel evidenceLevel = evidenceEvaluator.evaluate(merged, vectorResults.size(), ftsResults.size());

            // Step 6: Expand neighbors only when evidence is at least PARTIAL
            // Hits are reliable → neighbors add useful context.
            // WEAK/NONE → hits themselves are unreliable, skip to avoid amplifying noise.
            int totalCandidates = merged.size();
            if (evidenceLevel.ordinal() >= EvidenceLevel.PARTIAL.ordinal()) {
                merged = expandNeighbors(merged);
            }

            // Step 7: Build citations
            List<Citation> citations = citationBuilder.build(merged);

            log.info("Hybrid search complete: query={}, chunks={}, evidence={}, strategy={}",
                    actualQuery, merged.size(), evidenceLevel, plan.strategy());

            return new SearchResult(merged, evidenceLevel.name(), citations, actualQuery, plan.strategy(), totalCandidates);
        } finally {
            TtlMdcAdapter.remove("layer");
        }
    }

    /**
     * 将 chunk 的原始分数（RRF 或 Reranker 概率）归一化到 [0, 1] 区间。
     * Reranker 路径：finalScore 已经是 [-1, 1]，转换回 [0, 1]。
     * RRF 路径：rrfScore / 理论最大值 (2/(k+1))，上限 1.0。
     */
    private double score01ForChunk(ChunkResult r) {
        if (ragProperties.getRetrieval().isRerankEnabled()) {
            double s = r.getFinalScore();
            return Math.min(Math.max((s + 1.0) / 2.0, 0.0), 1.0);
        }
        double maxRrf = 2.0 / (ragProperties.getRetrieval().getRrfK() + 1);
        return Math.min(r.getRrfScore() / maxRrf, 1.0);
    }

    private List<ChunkResult> applyTimeDecay(List<ChunkResult> chunks) {
        if (chunks.isEmpty()) return chunks;

        for (ChunkResult r : chunks) {
            double base01 = score01ForChunk(r);
            if (ragProperties.getTimeDecay().isEnabled() && r.getMeetingDate() != null) {
                var config = ragProperties.getTimeDecay();
                long daysOld = ChronoUnit.DAYS.between(r.getMeetingDate(), LocalDate.now());
                double factor;
                if (daysOld <= config.getRecentDays()) factor = config.getRecentWeight();
                else if (daysOld <= 90)               factor = config.getNormalWeight();
                else if (daysOld <= 365)              factor = config.getOldWeight();
                else                                  factor = config.getArchiveWeight();
                base01 = Math.min(base01 * factor, 1.0);
            }
            r.setFinalScore(2.0 * base01 - 1.0);
        }
        return chunks;
    }

    private List<ChunkResult> applyDecayWithIntent(List<ChunkResult> chunks,
                                                    QueryPlanningService.TimeIntent intent,
                                                    String timeRange) {
        if (chunks.isEmpty()) return chunks;
        return switch (intent) {
            case RECENT -> applyTimeDecay(chunks);
            case HISTORICAL, NEUTRAL, RANGE -> {
                // Preserve semantic ranking, map to [-1, 1].
                // Reranker: finalScore already in [-1, 1], round-trip preserves it.
                // RRF: normalize to [-1, 1] via max possible RRF.
                for (ChunkResult r : chunks) {
                    double base01 = score01ForChunk(r);
                    r.setFinalScore(2.0 * base01 - 1.0);
                }
                yield chunks;
            }
        };
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
                    var parsed = ChunkMetadataParser.parse(n.getMetadata(), objectMapper);
                    expanded.add(ChunkResult.builder()
                            .chunkId(n.getId())
                            .documentId(n.getDocumentId())
                            .content(n.getContent())
                            .chunkIndex(n.getChunkIndex())
                            .speaker(n.getSpeaker())
                            .fileName(parsed.fileName())
                            .meetingDate(parsed.meetingDate())
                            .participants(parsed.participants())
                            .topic(parsed.topic())
                            .sectionHeading(parsed.sectionHeading())
                            .build());
                }
            }
        }
        return expanded;
    }
}
