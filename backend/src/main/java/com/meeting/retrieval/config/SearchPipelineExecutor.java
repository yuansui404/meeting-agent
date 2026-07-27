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

    private static final int TOPIC_EXPAND_LIMIT = 5;

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

    /**
     * 全文检索：对每个子查询并行执行 FullTextSearchService。
     * 结果参与后续 RRF 融合。
     */
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

    private void executeEvidenceEval(SearchContext context) {
        EvidenceLevel level = evidenceEvaluator.evaluate(context.getMerged());
        context.setEvidenceLevel(level);
    }

    private void executeNeighborExpand(SearchContext context) {
        if (context.getEvidenceLevel() == null) return;
        if (context.getEvidenceLevel().ordinal() < EvidenceLevel.PARTIAL.ordinal()) {
            log.debug("Evidence level {} below PARTIAL, skipping neighbor expansion",
                    context.getEvidenceLevel());
            return;
        }
        List<ChunkResult> expanded = expandNeighbors(context.getMerged(), context.getEvidenceLevel());
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

    private List<ChunkResult> expandNeighbors(List<ChunkResult> results, EvidenceLevel evidenceLevel) {
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

        // PARTIAL 时构建 topic 索引，用于同议题扩展
        boolean doTopicExpand = evidenceLevel == EvidenceLevel.PARTIAL;
        Map<String, List<DocumentChunkV2Entity>> topicIndex = null;
        if (doTopicExpand) {
            topicIndex = new HashMap<>();
            for (var entry : docChunks.entrySet()) {
                for (var entity : entry.getValue()) {
                    var parsed = ChunkMetadataParser.parse(entity.getMetadata(), objectMapper);
                    if (parsed.topic() != null && !parsed.topic().isEmpty()) {
                        topicIndex.computeIfAbsent(parsed.topic(), k -> new ArrayList<>()).add(entity);
                    }
                }
            }
        }

        for (ChunkResult r : results) {
            expanded.add(r);
            seenIds.add(r.getChunkId());
            if (r.getDocumentId() == null) continue;

            List<DocumentChunkV2Entity> neighbors = docChunks.get(r.getDocumentId());
            if (neighbors == null) continue;

            // 1. chunkIndex 相邻扩展（始终执行）
            for (var n : neighbors) {
                if (Math.abs(n.getChunkIndex() - r.getChunkIndex()) <= 1
                        && !seenIds.contains(n.getId())) {
                    seenIds.add(n.getId());
                    var parsed = ChunkMetadataParser.parse(n.getMetadata(), objectMapper);
                    var expandedChunk = buildExpandedChunk(n, parsed);
                    expanded.add(expandedChunk);
                }
            }

            // 2. topic 扩展（仅 PARTIAL 时）
            if (doTopicExpand && r.getTopic() != null && !r.getTopic().isEmpty()) {
                List<DocumentChunkV2Entity> sameTopic = topicIndex.get(r.getTopic());
                if (sameTopic != null) {
                    int added = 0;
                    for (var n : sameTopic) {
                        if (added >= TOPIC_EXPAND_LIMIT) break;
                        if (!seenIds.contains(n.getId())) {
                            seenIds.add(n.getId());
                            var parsed = ChunkMetadataParser.parse(n.getMetadata(), objectMapper);
                            var expandedChunk = buildExpandedChunk(n, parsed);
                            expanded.add(expandedChunk);
                            added++;
                        }
                    }
                }
            }
        }
        return expanded;
    }

    private ChunkResult buildExpandedChunk(DocumentChunkV2Entity entity, ChunkMetadataParser.ParsedMetadata parsed) {
        return ChunkResult.builder()
                .chunkId(entity.getId())
                .documentId(entity.getDocumentId())
                .content(entity.getContent())
                .chunkIndex(entity.getChunkIndex())
                .speaker(entity.getSpeaker())
                .fileName(parsed.fileName())
                .meetingDate(parsed.meetingDate())
                .participants(parsed.participants())
                .topic(parsed.topic())
                .sectionHeading(parsed.sectionHeading())
                .build();
    }
}