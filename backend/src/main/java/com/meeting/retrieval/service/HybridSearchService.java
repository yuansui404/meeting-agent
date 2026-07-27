package com.meeting.retrieval.service;

import com.meeting.common.TtlMdcAdapter;
import com.meeting.config.RagProperties;
import com.meeting.retrieval.config.SearchContext;
import com.meeting.retrieval.config.SearchPipelineExecutor;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import com.meeting.retrieval.model.EvidenceLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final QueryPlanningService queryPlanningService;
    private final SearchPipelineExecutor pipelineExecutor;
    private final RagProperties ragProperties;

    public record SearchResult(
            List<ChunkResult> chunks,
            String evidenceLevel,
            List<Citation> citations,
            String queryUsed,
            String strategyUsed,
            int totalCandidates
    ) {}

    public SearchResult search(String query) {
        TtlMdcAdapter.setLayer("RETRIEVAL");
        try {
            // 1. 查询规划（调用一次llm）
            QueryPlanningService.QueryPlan plan = planQuery(query);
            log.info("Query plan: strategy={}, rewritten={}, timeIntent={}, timeRange={}",
                    plan.strategy(), plan.rewrittenQuery(), plan.timeIntent(), plan.timeRange());

            // 2. 执行检索管线
            SearchContext context = executePipeline(query, plan);

            // 3. 低置信度重试
            if (shouldRetry(context)) {
                log.info("Evidence level {} is below SUFFICIENT, retrying with rewritten query",
                        context.getEvidenceLevel());
                String retryQuery = plan.rewrittenQuery() != null && !plan.rewrittenQuery().equals(query)
                        ? plan.rewrittenQuery() : query;
                String fallbackQuery = retryQuery + " 会议 讨论";
                log.info("Retry query: {}", fallbackQuery);
                QueryPlanningService.QueryPlan retryPlan = QueryPlanningService.QueryPlan.direct(fallbackQuery);
                SearchContext retryContext = executePipeline(fallbackQuery, retryPlan);
                if (retryContext.getEvidenceLevel() != null
                        && retryContext.getEvidenceLevel().ordinal() >= EvidenceLevel.PARTIAL.ordinal()) {
                    log.info("Retry succeeded with evidence level {}", retryContext.getEvidenceLevel());
                    context = retryContext;
                } else {
                    log.info("Retry also low confidence ({}), keeping original result",
                            retryContext.getEvidenceLevel());
                }
            }

            // 4. 构建结果
            return buildResult(context);
        } finally {
            TtlMdcAdapter.remove("layer");
        }
    }

    private boolean shouldRetry(SearchContext context) {
        if (context.getEvidenceLevel() == null) return false;
        if (!ragProperties.getSearch().isQueryRewriteEnabled()) return false;
        return context.getEvidenceLevel().ordinal() < EvidenceLevel.PARTIAL.ordinal();
    }

    private QueryPlanningService.QueryPlan planQuery(String query) {
        if (ragProperties.getSearch().isQueryRewriteEnabled()) {
            return queryPlanningService.plan(query);
        }
        return QueryPlanningService.QueryPlan.direct(query);
    }

    private SearchContext executePipeline(String query, QueryPlanningService.QueryPlan plan) {
        SearchContext context = new SearchContext(query, plan);
        pipelineExecutor.execute(context);
        log.info("Search pipeline complete: chunks={}, evidence={}, strategy={}",
                context.getMerged().size(),
                context.getEvidenceLevel() != null ? context.getEvidenceLevel().name() : "NONE",
                plan.strategy());
        return context;
    }

    private SearchResult buildResult(SearchContext context) {
        List<ChunkResult> chunks = context.getMerged();
        String evidenceLevel = context.getEvidenceLevel() != null
                ? context.getEvidenceLevel().name() : "NONE";
        List<Citation> citations = context.getCitations();
        String actualQuery = context.getActualQuery();
        int totalCandidates = context.getTotalCandidates();

        return new SearchResult(chunks, evidenceLevel, citations,
                actualQuery, context.getQueryPlan().strategy(), totalCandidates);
    }
}