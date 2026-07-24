package com.meeting.retrieval.config;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.Citation;
import com.meeting.retrieval.model.EvidenceLevel;
import com.meeting.retrieval.service.QueryPlanningService;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索管线执行上下文。
 * 携带管线执行过程中的可变状态，在各步骤间传递。
 */
@Data
public class SearchContext {
    // ── 输入 ──
    private final String query;
    private final QueryPlanningService.QueryPlan queryPlan;

    // ── 中间状态 ──
    private List<ChunkResult> vectorResults = new ArrayList<>();
    private List<ChunkResult> ftsResults = new ArrayList<>();
    private List<ChunkResult> merged = new ArrayList<>();
    private EvidenceLevel evidenceLevel;
    private List<Citation> citations = new ArrayList<>();
    private int totalCandidates;

    public SearchContext(String query, QueryPlanningService.QueryPlan queryPlan) {
        this.query = query;
        this.queryPlan = queryPlan;
    }

    public String getActualQuery() {
        return queryPlan.rewrittenQuery();
    }
}