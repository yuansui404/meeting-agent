package com.meeting.retrieval.config;

import com.meeting.common.TtlMdcAdapter;
import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentChunkV2Entity;
import com.meeting.document.repository.DocumentChunkV2Repository;
import com.meeting.retrieval.algorithm.CitationBuilder;
import com.meeting.retrieval.algorithm.DocumentDeduplicator;
import com.meeting.retrieval.algorithm.EvidenceEvaluator;
import com.meeting.retrieval.algorithm.RrfMerger;
import com.meeting.retrieval.model.ChunkMetadataParser;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import com.meeting.retrieval.model.EvidenceLevel;
import com.meeting.retrieval.service.FullTextSearchService;
import com.meeting.retrieval.service.QueryPlanningService;
import com.meeting.retrieval.service.Reranker;
import com.meeting.retrieval.service.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchPipelineExecutor {

    private final VectorSearchService vectorSearchService;
    private final FullTextSearchService fullTextSearchService;
    private final Reranker reranker;
    private final RrfMerger rrfMerger;
    private final EvidenceEvaluator evidenceEvaluator;
    private final CitationBuilder citationBuilder;
    private final DocumentDeduplicator documentDeduplicator;
    private final RagProperties ragProperties;
    private final DocumentChunkV2Repository chunkV2Repository;
    private final ObjectMapper objectMapper;
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor taskExecutor;

    /**
     * 执行检索管线。
     * 根据配置的 4 个开关决定执行路径：
     * <ul>
     *   <li>method: vector-only | hybrid</li>
     *   <li>rerank-enabled: 是否重排序</li>
     * </ul>
     */
    public void execute(SearchContext context) {
        RagProperties.Search searchConfig = ragProperties.getSearch();

        // 1. 向量检索（始终执行）
        log.info("Pipeline step: VECTOR_SEARCH (method={})", searchConfig.getMethod());
        executeVectorSearch(context);

        // 2. 融合查询：全文检索 + RRF 融合
        if ("hybrid".equals(searchConfig.getMethod())) {
            log.info("Pipeline step: FULL_TEXT_SEARCH + RRF_MERGE");
            executeFullTextSearch(context);
            executeRrfMerge(context);
        } else {
            // vector-only：向量结果直接作为 merged
            context.setMerged(new ArrayList<>(context.getVectorResults()));
            context.setTotalCandidates(context.getVectorResults().size());
        }

        // 3. 重排序（可选）
        if (searchConfig.isRerankEnabled()) {
            log.info("Pipeline step: RERANK");
            executeRerank(context);
        }

        // 4. 固定步骤
        log.info("Pipeline step: TIME_DECAY");
        executeTimeDecay(context);
        log.info("Pipeline step: EVIDENCE_EVAL");
        executeEvidenceEval(context);
        log.info("Pipeline step: NEIGHBOR_EXPAND");
        executeNeighborExpand(context);
        log.info("Pipeline step: CITATION");
        executeCitation(context);
    }

    // ── 各步骤实现 ──

    private void executeVectorSearch(SearchContext context) {
        int topK = ragProperties.getRetrieval().getVectorTopk();
        QueryPlanningService.QueryPlan plan = context.getQueryPlan();

        List<CompletableFuture<List<ChunkResult>>> futures = new ArrayList<>();
        for (String subQuery : plan.subQueries()) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    vectorSearchService.search(subQuery, topK), taskExecutor));
        }
        List<ChunkResult> results = new ArrayList<>();
        for (CompletableFuture<List<ChunkResult>> f : futures) {
            results.addAll(f.join());
        }
        context.setVectorResults(results);
    }

    private void executeFullTextSearch(SearchContext context) {
        int topK = ragProperties.getRetrieval().getFtsTopk();
        QueryPlanningService.QueryPlan plan = context.getQueryPlan();

        List<CompletableFuture<List<ChunkResult>>> futures = new ArrayList<>();
        for (String subQuery : plan.subQueries()) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    fullTextSearchService.search(subQuery, topK), taskExecutor));
        }
        List<ChunkResult> results = new ArrayList<>();
        for (CompletableFuture<List<ChunkResult>> f : futures) {
            results.addAll(f.join());
        }
        context.setFtsResults(results);
    }

    private void executeRrfMerge(SearchContext context) {
        int k = ragProperties.getRetrieval().getRrfK();
        List<ChunkResult> merged = rrfMerger.merge(
                context.getVectorResults(), context.getFtsResults(), k);
        context.setMerged(merged);
        context.setTotalCandidates(merged.size());
    }

    private void executeRerank(SearchContext context) {
        int topN = ragProperties.getRetrieval().getRerankTopk();
        List<ChunkResult> reranked = reranker.reRank(
                context.getActualQuery(), context.getMerged(), topN);
        context.setMerged(reranked);
    }

    private void executeTimeDecay(SearchContext context) {
        List<ChunkResult> chunks = context.getMerged();
        if (chunks.isEmpty()) return;

        QueryPlanningService.TimeIntent intent = context.getQueryPlan().timeIntent();
        RagProperties.TimeDecay config = ragProperties.getTimeDecay();

        for (ChunkResult r : chunks) {
            double base01 = score01ForChunk(r);
            if (config.isEnabled() && intent == QueryPlanningService.TimeIntent.RECENT
                    && r.getMeetingDate() != null) {
                long daysOld = ChronoUnit.DAYS.between(r.getMeetingDate(), LocalDate.now());
                double factor;
                if (daysOld <= config.getRecentDays())      factor = config.getRecentWeight();
                else if (daysOld <= 90)                     factor = config.getNormalWeight();
                else if (daysOld <= 365)                    factor = config.getOldWeight();
                else                                        factor = config.getArchiveWeight();
                base01 = Math.min(base01 * factor, 1.0);
            }
            r.setFinalScore(2.0 * base01 - 1.0);
        }
        context.setMerged(chunks);
    }

    private void executeEvidenceEval(SearchContext context) {
        EvidenceLevel level = evidenceEvaluator.evaluate(
                context.getMerged(), context.getVectorResults(), context.getFtsResults());
        context.setEvidenceLevel(level);
    }

    private void executeNeighborExpand(SearchContext context) {
        if (context.getEvidenceLevel() == null) return;
        if (context.getEvidenceLevel().ordinal() < EvidenceLevel.PARTIAL.ordinal()) {
            log.debug("Evidence level {} below PARTIAL, skipping neighbor expansion",
                    context.getEvidenceLevel());
            return;
        }
        List<ChunkResult> expanded = expandNeighbors(context.getMerged());
        context.setMerged(expanded);
    }

    private void executeDedup(SearchContext context) {
        int topN = ragProperties.getRetrieval().getRerankTopk();
        List<ChunkResult> deduped = documentDeduplicator.deduplicate(context.getMerged(), topN);
        context.setMerged(deduped);
    }

    private void executeCitation(SearchContext context) {
        List<Citation> citations = citationBuilder.build(context.getMerged());
        context.setCitations(citations);
    }

    // ── 工具方法 ──

    private double score01ForChunk(ChunkResult r) {
        if (ragProperties.getSearch().isRerankEnabled()) {
            double s = r.getFinalScore();
            return Math.min(Math.max((s + 1.0) / 2.0, 0.0), 1.0);
        }
        double maxRrf = 2.0 / (ragProperties.getRetrieval().getRrfK() + 1);
        return Math.min(r.getRrfScore() / maxRrf, 1.0);
    }

    private List<ChunkResult> expandNeighbors(List<ChunkResult> results) {
        List<ChunkResult> expanded = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        Map<Long, List<DocumentChunkV2Entity>> docChunks = new HashMap<>();
        Set<Long> docIds = results.stream()
                .map(ChunkResult::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!docIds.isEmpty()) {
            try {
                List<DocumentChunkV2Entity> allChunks = chunkV2Repository
                        .findByDocumentIdInOrderByChunkIndex(new ArrayList<>(docIds));
                docChunks = allChunks.stream()
                        .collect(Collectors.groupingBy(DocumentChunkV2Entity::getDocumentId));
            } catch (Exception e) {
                log.warn("Batch neighbor fetch failed, falling back to individual queries", e);
            }
        }

        for (ChunkResult r : results) {
            expanded.add(r);
            seenIds.add(r.getChunkId());
            if (r.getDocumentId() == null) continue;

            List<DocumentChunkV2Entity> neighbors = docChunks.get(r.getDocumentId());
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